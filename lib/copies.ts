// Every copy of a film or episode the app could play (KTD1, KTD3, KTD5): the provider's (IPTV) and the
// torrents Torrentio knows (Real-Debrid candidates; the stick checks which are cached). The box
// describes, the stick ranks. IPTV copies never wait on Torrentio: it gets a short budget, after which
// the answer says `sources.rd: 'pending'` and the lookup carries on into the cache for the next ask.
// Arabic films, Arabic-dubbed animated films and animated shows' episodes also get ArabP2P's copies that TorBox holds (arabp2p.ts).
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { arabp2pCopies, arabp2pEpisodeCopies } from './arabp2p.js'
import { writeAtomic } from './fs.js'
import { getJson } from './http.js'
import { introFor, type Intro } from './intros.js'
import { languageClaim, readName, type XtreamConfig } from './provider.js'
import { claimedPicture, measuredStream, streamLabels, type Measured, type ProbedStream } from './quality-labels.js'
import { flagLanguages, parseRelease, type Attrs } from './release-name.js'
import { episodeEntries, measuredFilms } from './shows.js'
import { parseTitleId, tmdbTitle } from './tmdb.js'
import { filmEntries } from './vod.js'

export interface IptvCopy extends Attrs {
  src: 'iptv'
  /** VOD or episode stream id. */
  id: number
  ext: string
  /** A film's provider name, tags included ("4K-EN - Inception (2010)"); an episode's show category. */
  name: string
  /** Where the attributes come from: the app's own reading, the provider's probe, or the name's and category's claim. */
  basis: 'measured' | 'probed' | 'claimed'
  /** 0 unknown. */
  kbps: number
}

export interface RdCopy extends Attrs {
  src: 'rd'
  hash: string
  /** Torrentio's file index and file name inside the torrent. */
  file?: number
  filename?: string
  /** The release name. */
  name: string
  /** Bytes. */
  size: number
  seeds: number
  /** Torrentio's "[RD+]": someone resolved it on Real-Debrid lately, so it is likely ready. Absent: unknown. */
  cached?: true
  /** Size over the title's TMDB runtime; 0 unknown. */
  kbps: number
}

type Status = 'ok' | 'pending' | 'unavailable'
export interface Copies {
  titleId: string
  /** ISO 639-1 from TMDB; '' unknown. */
  originalLanguage: string
  /** Minutes (an episode's for shows); 0 unknown. */
  runtime: number
  sources: { iptv: Status; rd: Status }
  /** An episode's intro, for Skip intro; absent when unknown or not in time. */
  intro?: Intro
  copies: (IptvCopy | RdCopy)[]
}

// The provider's language tag ("4K-DE - …", "AR-SUBS - …", "AR-DUB - …", category "ES - PELÍCULAS") adds the
// audio or subtitles it claims (see languageClaim, readName).
function withTag(a: Attrs, { dubbed, subbed }: { dubbed?: string; subbed?: string }): Attrs {
  if (dubbed) return { ...a, langs: [...new Set([dubbed, ...a.langs])] }
  if (subbed) return { ...a, subs: [...new Set([subbed, ...(a.subs ?? [])])] }
  return a
}

const tagOf = (name: string, category: string) => readName(name, category)

/** A probed stream's attributes, read through its labels ("4K", "Dolby Vision", "HEVC", "Atmos", "E-AC3 5.1"), with the probe's Dolby Vision profile. */
function probed(s: ProbedStream): Attrs {
  const a = parseRelease(streamLabels(s).filter((l) => !l.endsWith('Mbps')).join(' '))
  const profile = Number(s.video?.side_data_list?.find((d) => d.dv_profile != null)?.dv_profile)
  return a.hdr.includes('dv') && profile > 0 ? { ...a, dvProfile: profile } : a
}

/** A catalogue copy of a film: as the app measured it, else what its name and category claim. */
export function filmCopy(e: { id: number; name: string; category: string; ext: string }, m?: Measured): IptvCopy {
  const base = { src: 'iptv' as const, id: e.id, ext: e.ext, name: e.name }
  const tag = tagOf(e.name, e.category)
  if (m) return { ...base, basis: 'measured', ...withTag(probed(measuredStream(m)), tag), kbps: m.kbps ?? 0 }
  const a = parseRelease(`${e.name}\n${e.category}`)
  return { ...base, basis: 'claimed', ...withTag({ ...a, res: a.res || (/^4K\b/.test(e.name) ? 2160 : claimedPicture(e.category)?.height ?? 0) }, tag), kbps: 0 }
}

/** The provider's copies of a film (`movie:<tmdb>`, `iptv:<id>`) or an episode (`…:s<n>e<n>`, one per language its show is listed in). */
export async function iptvCopies(cfg: XtreamConfig, title: string): Promise<IptvCopy[]> {
  const ep = title.match(/^(.+):s(\d+)e(\d+)$/)
  if (ep) {
    return (await episodeEntries(cfg, ep[1], Number(ep[2]), Number(ep[3]))).map((e) => {
      const known = !!(e.probe?.video && Object.keys(e.probe.video).length)
      return { src: 'iptv', id: e.id, ext: e.ext, name: e.category, basis: known ? 'probed' : 'claimed', ...withTag(known ? probed(e.probe!) : parseRelease(e.category), languageClaim(e.lang)), kbps: Math.round(Number(e.probe?.bitrate) || 0) }
    })
  }
  const [entries, measured] = await Promise.all([filmEntries(cfg, title), measuredFilms()])
  return entries.map((e) => filmCopy(e, measured[String(e.id)]))
}

interface Stream { name?: string; title?: string; infoHash?: string; fileIdx?: number; url?: string; behaviorHints?: { filename?: string } }
type Candidate = Omit<RdCopy, 'kbps'>
const UNIT: Record<string, number> = { KB: 2 ** 10, MB: 2 ** 20, GB: 2 ** 30, TB: 2 ** 40 }

/**
 * Torrentio's streams as candidates, one per torrent file; CAM, telesync, screener and 3D releases left
 * out. A title reads "<release>[\n<file in a pack>]\n👤 seeds 💾 size ⚙️ tracker[\n<audio flags>]"; the file name
 * (behaviorHints) is read with it, as it often names the audio the release doesn't. The
 * debrid list carries hash and file index only in its resolve URL (`/resolve/realdebrid/<key>/<hash>/null/<fileIdx>/…`,
 * `/resolve/torbox/<key>/<hash>/<name>/<fileIdx>/…`), which is read for them and then dropped with the key it holds;
 * "[RD+]" / "[TB+]" mark a copy the debrid holds. TorrentsDB answers in the same format, without the debrid flags.
 */
export function torrentioCandidates(streams: Stream[]): Candidate[] {
  const seen = new Set<string>()
  return streams.flatMap((s) => {
    const [label = '', quality = ''] = (s.name ?? '').split('\n')
    const lines = (s.title ?? '').split('\n')
    const stats = lines.findIndex((l) => l.includes('💾'))
    const resolve = s.url?.match(/\/resolve\/(?:realdebrid|torbox)\/[^/]+\/([0-9a-f]{40})\/[^/]+\/([^/]+)\//i)
    const hash = (s.infoHash ?? resolve?.[1] ?? '').toLowerCase()
    const file = s.fileIdx ?? (/^\d+$/.test(resolve?.[2] ?? '') ? Number(resolve![2]) : undefined)
    const key = `${hash}:${file}`
    if (!hash || stats < 1 || seen.has(key) || /^(cam|telesync|telecine|scr|3d)/i.test(quality)) return []
    seen.add(key)
    const size = lines[stats].match(/💾 ([\d.]+) ([KMGT]B)/)
    const flags = lines.slice(stats + 1).join(' ')
    const a = parseRelease([...lines.slice(0, stats), quality, s.behaviorHints?.filename ?? ''].join('\n'))
    const subs = [...(a.subs ?? []), ...(/multi subs/i.test(flags) ? ['multi'] : [])]
    return [{
      src: 'rd' as const,
      hash,
      ...(file != null && { file }),
      ...(s.behaviorHints?.filename && { filename: s.behaviorHints.filename }),
      name: lines[0],
      size: size ? Math.round(Number(size[1]) * UNIT[size[2]]) : 0,
      seeds: Number(lines[stats].match(/👤 (\d+)/)?.[1] ?? 0),
      ...(/^\[(?:RD|TB)\+\]/.test(label) && { cached: true as const }),
      ...a,
      langs: [...new Set([...a.langs, ...flagLanguages(flags)])],
      ...(subs.length ? { subs: [...new Set(subs)] } : {}),
    }]
  })
}

const readJson = <T>(file: string, empty: T) => readFile(file, 'utf8').then((s) => JSON.parse(s) as T).catch(() => empty)

// Torrentio answers per IMDb id (and season and episode), asked with the debrid key (TorBox's, else
// Real-Debrid's) so copies carry its "[TB+]" / "[RD+]" flag, else, without a key or when that fails, bare.
// TorrentsDB (Torrentio's format, its own trackers and servers) is asked alongside and merged in after
// Torrentio's copies, so either one being down costs nothing; an answer missing one is re-asked after 5 min.
// Cached 2 h, as the flags change, in this process and on disk (so a restart stays warm); an older answer
// is served at once while a fresh one is fetched, and kept a week for when both fail. A failed lookup isn't
// retried for 5 minutes. The key never leaves this function.
const FRESH_MS = 2 * 3600_000
const KEEP_MS = 7 * 864e5
const RETRY_MS = 5 * 60_000
const cacheFile = () => process.env.TORRENTIO_CACHE_FILE ?? fileURLToPath(new URL('../.cache/torrentio.json', import.meta.url))
type Entry = { at: number; copies: Candidate[] }
let store: Promise<Record<string, Entry>> | null = null
const pending = new Map<string, Promise<Candidate[]>>()
const failed = new Map<string, number>()

async function torrentio(path: string): Promise<Candidate[]> {
  const all = await (store ??= readJson<Record<string, Entry>>(cacheFile(), {}))
  const hit = all[path]
  if ((hit && Date.now() - hit.at < FRESH_MS) || Date.now() - (failed.get(path) ?? 0) < RETRY_MS) {
    if (hit) return hit.copies
    throw new Error('Torrentio failed recently')
  }
  let job = pending.get(path)
  if (!job) {
    const key = process.env.TORBOX_TOKEN ? `torbox=${process.env.TORBOX_TOKEN}/` : process.env.REAL_DEBRID_TOKEN ? `realdebrid=${process.env.REAL_DEBRID_TOKEN}/` : ''
    const list = (url: string) => getJson<{ streams?: Stream[] }>(`${url}stream/${path}.json`, { timeoutMs: 8000, retries: 1 })
    const primary = list(`https://torrentio.strem.fun/${key}`)
    job = Promise.allSettled([key ? primary.catch(() => list('https://torrentio.strem.fun/')) : primary, list('https://torrentsdb.com/')])
      .then((answers) => {
        const got = answers.flatMap((a) => (a.status === 'fulfilled' ? [a.value.streams ?? []] : []))
        if (!got.length) throw (answers[0] as PromiseRejectedResult).reason
        all[path] = { at: Date.now() - (got.length < answers.length ? FRESH_MS - RETRY_MS : 0), copies: torrentioCandidates(got.flat()) }
        for (const [k, e] of Object.entries(all)) if (Date.now() - e.at > KEEP_MS) delete all[k]
        writeAtomic(cacheFile(), all).catch(() => {})
        return all[path].copies
      }, (e) => {
        failed.set(path, Date.now())
        throw e
      })
      .finally(() => pending.delete(path))
    pending.set(path, job)
  }
  if (hit) {
    job.catch(() => {})
    return hit.copies
  }
  return job
}

// Torrent hashes Real-Debrid refused here (451 infringing_file), posted by the stick: dropped from every
// title's candidates for 60 days, so no prewarm tries them again.
const DISC = /complete[ ._-]*(?:uhd[ ._-]*)?blu-?ray\b|\bbdmv\b|\.iso\b|\bbd-?(?:25|50|66|100)\b/i
const INFRINGING_MS = 60 * 864e5
const infringingFile = () => process.env.RD_INFRINGING_FILE ?? fileURLToPath(new URL('../.cache/rd-infringing.json', import.meta.url))
let refused: Promise<Record<string, number>> | null = null
const infringing = () => (refused ??= readJson<Record<string, number>>(infringingFile(), {}))

/** Keeps [hashes] as refused; true when any was new. */
export async function markInfringing(hashes: string[]): Promise<boolean> {
  const all = await infringing()
  const fresh = hashes.map((h) => h.toLowerCase()).filter((h) => !all[h])
  if (!fresh.length) return false
  for (const h of fresh) all[h] = Date.now()
  for (const [h, at] of Object.entries(all)) if (Date.now() - at > INFRINGING_MS) delete all[h]
  await writeAtomic(infringingFile(), all).catch(() => {})
  return true
}

/**
 * A title's copies: the IPTV ones (given, so the caller picks how they're found) and the Real-Debrid
 * candidates, which get [waitMs] before the answer goes out without them (`sources.rd: 'pending'`).
 */
export async function titleCopies(title: string, iptv: Promise<IptvCopy[]>, waitMs = 2500): Promise<Copies> {
  const p = parseTitleId(title)
  const meta = p ? tmdbTitle(p.kind, p.tmdb) : Promise.resolve(null)
  let arabp2pPending = false
  const rd = meta.then(async (t): Promise<RdCopy[]> => {
    if (!p) return [] // a provider-only title: nothing to look up
    if (!t) throw new Error('no TMDB details')
    if (!t.imdb) return []
    const [copies, extra, refused] = await Promise.all([
      torrentio(p.season != null ? `series/${t.imdb}:${p.season}:${p.episode}` : `movie/${t.imdb}`),
      p.kind === 'movie' ? arabp2pCopies(p.tmdb, t) : p.season != null ? arabp2pEpisodeCopies(p.tmdb, t, p.season, p.episode!) : { copies: [], pending: false },
      infringing(),
    ])
    arabp2pPending = extra.pending
    const now = Date.now()
    const known = new Set(copies.map((c) => c.hash))
    return [...copies, ...extra.copies.filter((c) => !known.has(c.hash))]
      .filter((c) => !(now - (refused[c.hash] ?? 0) < INFRINGING_MS))
      // A full disc ("COMPLETE.BLURAY", BDMV, ISO) is a folder of disc files, not one video the stick can stream.
      .filter((c) => !DISC.test(`${c.name} ${c.filename ?? ''}`))
      .map((c) => ({ ...c, kbps: t.runtime > 0 ? Math.round((c.size * 8) / (t.runtime * 60) / 1000) : 0 }))
  })
  const intro = p?.season != null ? meta.then((t) => introFor(title, t?.imdb)) : Promise.resolve(null)
  const late = Bun.sleep(waitMs).then(() => 'pending' as const)
  const settle = <T>(p: Promise<T>) => Promise.race([p.then((v) => ({ v }), () => 'unavailable' as const), late])
  const [i, r, t, o] = await Promise.all([iptv.then((v) => ({ v }), () => 'unavailable' as const), settle(rd), settle(meta), settle(intro)])
  const tmdb = typeof t === 'object' ? t.v : null
  return {
    titleId: title,
    originalLanguage: tmdb?.originalLanguage ?? '',
    runtime: tmdb?.runtime ?? 0,
    // An ArabP2P lookup still running makes the debrid side pending, so the copies aren't kept without what it finds.
    sources: { iptv: typeof i === 'object' ? 'ok' : i, rd: typeof r === 'object' ? (arabp2pPending ? 'pending' : 'ok') : r },
    ...(typeof o === 'object' && o.v && { intro: o.v }),
    copies: [...(typeof i === 'object' ? i.v : []), ...(typeof r === 'object' ? r.v : [])],
  }
}
