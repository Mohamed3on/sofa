// Subtitles for the copy playing (R30, KTD18 as the owner changed it on 2026-11-05): Stremio's public OpenSubtitles
// addon first (no key, no account, no quota), matched by the file's OpenSubtitles hash when the stick sends it; the
// official OpenSubtitles API only as a second source when OPENSUBTITLES_KEY/USER/PASS are set. English first,
// Arabic a distant second. A title's list is kept per IMDb id and hash, each subtitle file for good, both on disk.
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { writeAtomic } from './fs.js'
import { getJson, HttpError } from './http.js'

export interface Sub {
  /** An addon file number ("1952382875"), or "os<file_id>" from the official API. */
  id: string
  lang: 'en' | 'ar'
  /** Matched by the file's hash: made for this very file. */
  hash: boolean
  release: string
}
/** "quota": the source refused for today ("No subtitles today"). */
export interface SubsAnswer { status: 'ok' | 'none' | 'quota'; subs: Sub[] }

const ADDON = 'https://opensubtitles-v3.strem.io/subtitles'
const ADDON_FILE = 'https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/'
const OS = 'https://api.opensubtitles.com/api/v1'
const LANGS: Record<string, Sub['lang']> = { eng: 'en', ara: 'ar', en: 'en', ar: 'ar' }
const LIST_MS = 7 * 864e5
const EMPTY_MS = 864e5
const dir = () => process.env.SUBTITLES_DIR ?? fileURLToPath(new URL('../.cache/subtitles', import.meta.url))
const file = (name: string) => `${dir()}/${name.replace(/[^\w.-]/g, '_')}`

const tokens = (s: string) => new Set(s.toLowerCase().split(/[^a-z0-9]+/).filter((t) => t.length > 1))
/** How much of a release name two names share (0–1). */
function likeness(a: Set<string>, b: string) {
  const t = tokens(b)
  let both = 0
  for (const x of t) if (a.has(x)) both++
  return both / (a.size + t.size - both || 1)
}

/** English before Arabic; a hash match first, then the release name nearest the copy's, then the source's order. */
export function pick(subs: Sub[], name: string, per = 3): Sub[] {
  const mine = tokens(name)
  const ranked = subs.map((s, i) => ({ s, i, l: likeness(mine, s.release) }))
    .sort((a, b) => (a.s.lang === b.s.lang ? 0 : a.s.lang === 'en' ? -1 : 1) || +b.s.hash - +a.s.hash || b.l - a.l || a.i - b.i)
  const out: Sub[] = []
  for (const { s } of ranked) if (out.filter((o) => o.lang === s.lang).length < (s.lang === 'en' ? per : 2)) out.push(s)
  return out
}

interface AddonSub { url?: string; lang?: string; m?: string; movieReleaseName?: string; subtitleFileName?: string }

/** `title` is the addon's id: "tt1375666" for a film, "tt0903747:1:1" for an episode. */
async function fromAddon(title: string, hash: string, size: number): Promise<Sub[]> {
  const kind = title.includes(':') ? 'series' : 'movie'
  const extra = hash ? `/videoHash=${hash}&videoSize=${size}` : ''
  const { subtitles = [] } = await getJson<{ subtitles?: AddonSub[] }>(`${ADDON}/${kind}/${title}${extra}.json`, { retries: 1 })
  return subtitles.flatMap((s) => {
    const id = s.url?.match(/\/file\/(\d+)$/)?.[1]
    const lang = LANGS[s.lang ?? '']
    return id && lang ? [{ id, lang, hash: s.m === 'h', release: s.movieReleaseName || s.subtitleFileName || '' }] : []
  })
}

const official = () => {
  const { OPENSUBTITLES_KEY: key, OPENSUBTITLES_USER: user, OPENSUBTITLES_PASS: pass } = process.env
  return key && user && pass ? { key, user, pass } : null
}
const osHeaders = (key: string) => ({ 'Api-Key': key, 'User-Agent': 'iptv-preview v1', 'Content-Type': 'application/json' })

async function fromOfficial(title: string, hash: string): Promise<Sub[]> {
  const o = official()
  if (!o) return []
  const [imdb, season, episode] = title.split(':')
  const q = season ? `parent_imdb_id=${+imdb.slice(2)}&season_number=${season}&episode_number=${episode}` : `imdb_id=${+imdb.slice(2)}`
  type Found = { attributes: { language: string; release?: string; moviehash_match?: boolean; files?: { file_id: number }[] } }
  const { data = [] } = await getJson<{ data?: Found[] }>(`${OS}/subtitles?${q}&languages=ar,en${hash ? `&moviehash=${hash}` : ''}`, { headers: osHeaders(o.key), retries: 1 })
  return data.flatMap(({ attributes: a }) => {
    const lang = LANGS[a.language]
    const id = a.files?.[0]?.file_id
    return lang && id ? [{ id: `os${id}`, lang, hash: !!a.moviehash_match, release: a.release ?? '' }] : []
  })
}

/** The subtitles for an addon title id and the file's hash ("" when the stick has none), best first for the copy [name]. */
export async function subtitlesFor(title: string, hash: string, size: number, name: string): Promise<SubsAnswer> {
  const cached = file(`list-${title}-${hash}.json`)
  const kept = await readFile(cached, 'utf8').then((s) => JSON.parse(s) as { at: number; subs: Sub[] }).catch(() => null)
  if (kept && Date.now() - kept.at < (kept.subs.length ? LIST_MS : EMPTY_MS)) return { status: kept.subs.length ? 'ok' : 'none', subs: pick(kept.subs, name) }
  let subs: Sub[]
  try {
    subs = await fromAddon(title, hash, size)
    if (!subs.some((s) => s.lang === 'en')) subs = [...subs, ...(await fromOfficial(title, hash).catch(() => []))]
  } catch (e) {
    return { status: e instanceof HttpError && e.status === 429 ? 'quota' : 'none', subs: [] }
  }
  await writeAtomic(cached, { at: Date.now(), subs })
  return { status: subs.length ? 'ok' : 'none', subs: pick(subs, name) }
}

/** A subtitle file's SRT text (UTF-8), kept on disk once fetched; "quota" when today's downloads are spent. */
export async function subtitleFile(id: string): Promise<string | 'quota'> {
  const path = file(`${id}.srt`)
  const cached = Bun.file(path)
  if (await cached.exists()) return cached.text()
  let url = `${ADDON_FILE}${id}`
  if (id.startsWith('os')) {
    const o = official()
    if (!o) throw new Error('OpenSubtitles not set up')
    const post = async (path: string, body: object, token?: string) => {
      const r = await fetch(`${OS}/${path}`, { method: 'POST', body: JSON.stringify(body), headers: { ...osHeaders(o.key), ...(token && { Authorization: `Bearer ${token}` }) }, signal: AbortSignal.timeout(10_000) })
      if (!r.ok) throw new HttpError(r.status)
      return r.json()
    }
    try {
      const { token } = await post('login', { username: o.user, password: o.pass })
      url = (await post('download', { file_id: +id.slice(2) }, token)).link
    } catch (e) {
      if (e instanceof HttpError && (e.status === 406 || e.status === 429)) return 'quota'
      throw e
    }
  }
  const r = await fetch(url, { signal: AbortSignal.timeout(15_000) })
  if (!r.ok) throw new HttpError(r.status)
  const text = await r.text()
  await writeAtomic(path, text)
  return text
}
