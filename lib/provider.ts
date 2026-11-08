// The one way to the Xtream provider: its config (accounts A and B, the VPN front), API calls,
// stream URLs, the saved catalogue snapshots (VOD and series), and what its names and categories
// mean. Curation, the VOD reader, series and the quality probe reach the provider only through here.
import { fileURLToPath } from 'node:url'
import { jsonFile } from './fs.js'

export interface XtreamConfig {
  host: string
  user: string
  pass: string
}

/** The provider's VPN-only front: the normal fronts block VPN IPs, and this one blocks everyone else. */
export const VPN_HOST = 'http://apps.g1ming-hub.sbs'

export function configFromEnv(): XtreamConfig {
  const { XTREAM_HOST, XTREAM_USER, XTREAM_PASS } = process.env
  if (!XTREAM_HOST || !XTREAM_USER || !XTREAM_PASS) {
    throw new Error('Missing XTREAM_HOST/XTREAM_USER/XTREAM_PASS env vars')
  }
  return { host: XTREAM_HOST.replace(/\/$/, ''), user: XTREAM_USER, pass: XTREAM_PASS }
}

/** A backup subscription (provider B): its plain front, its VPN front (the plain one unless it has its own) and account. */
export interface BackupConfig extends XtreamConfig {
  vpnHost: string
}

/** Provider B from XTREAM2_HOST/USER/PASS (+ optional XTREAM2_VPN_HOST); undefined until they are set. */
export function backupConfigFromEnv(): BackupConfig | undefined {
  const { XTREAM2_HOST, XTREAM2_USER, XTREAM2_PASS, XTREAM2_VPN_HOST } = process.env
  if (!XTREAM2_HOST || !XTREAM2_USER || !XTREAM2_PASS) return undefined
  const host = XTREAM2_HOST.replace(/\/$/, '')
  return { host, vpnHost: XTREAM2_VPN_HOST?.replace(/\/$/, '') || host, user: XTREAM2_USER, pass: XTREAM2_PASS }
}

/** Provider B's stream ids are offset by this, so they never collide with A's (quality store, app ids). */
export const BACKUP_ID = 1_000_000_000

// Every provider call and guide download goes through download(): one not done, body included, in
// timeoutMs fails, so a hung provider fails a build instead of leaving every build waiting on it. A
// whole-download limit, not a stall timer: piping the ~70 MB guide through a JS stream to watch its
// bytes cost ~0.2 GB more peak on the box than Bun's own body read.
let fetchImpl: typeof fetch = (...a) => fetch(...a)
let timeoutMs = 120_000

/** Tests: answer provider calls with [f] (and a shorter time limit); returns the undo. */
export function useProviderFetch(f: (url: string, init?: RequestInit) => Promise<Response>, timeout = timeoutMs): () => void {
  const was = [fetchImpl, timeoutMs] as const
  fetchImpl = f as typeof fetch
  timeoutMs = timeout
  return () => { [fetchImpl, timeoutMs] = was }
}

/** A GET of the provider or a guide, aborted with its body when not done in timeoutMs. */
export const download = (url: string): Promise<Response> =>
  fetchImpl(url, { headers: { 'User-Agent': 'VLC/3.0.18' }, signal: AbortSignal.timeout(timeoutMs) })

/** A player_api.php call ("get_live_streams", "get_vod_info&vod_id=1"); no action answers the account's status. */
export async function apiGet<T>(cfg: XtreamConfig, action: string): Promise<T> {
  const url = `${cfg.host}/player_api.php?username=${cfg.user}&password=${cfg.pass}${action ? `&action=${action}` : ''}`
  const resp = await download(url)
  if (!resp.ok) {
    void resp.body?.cancel().catch(() => {})
    throw new Error(`${action} -> HTTP ${resp.status}`)
  }
  return resp.json() as Promise<T>
}

export interface LiveCategory { category_id: string; category_name: string }
export interface LiveStream { stream_id: number; name: string; stream_icon?: string; epg_channel_id?: string | null; category_id: string }

// An account's live list (~56k streams on B) is asked for by the playlist, the app's channels and the
// guide at once: one download per account is in flight, and nothing is kept once it ends.
const liveLists = new Map<string, Promise<{ cats: LiveCategory[]; streams: LiveStream[] }>>()

/** The account's live categories and streams, shared with every caller asking while it downloads. */
export function liveList(cfg: XtreamConfig): Promise<{ cats: LiveCategory[]; streams: LiveStream[] }> {
  const k = `${cfg.host}|${cfg.user}`
  let job = liveLists.get(k)
  if (!job) {
    job = Promise.all([apiGet<LiveCategory[]>(cfg, 'get_live_categories'), apiGet<LiveStream[]>(cfg, 'get_live_streams')])
      .then(([cats, streams]) => ({ cats, streams }))
      .finally(() => liveLists.delete(k))
    liveLists.set(k, job)
  }
  return job
}

/** The account's connection slots: how many it may hold, and how many are in use. */
export async function connectionSlots(cfg: XtreamConfig): Promise<{ max: number; active: number }> {
  const info = (await apiGet<{ user_info?: { max_connections?: string; active_cons?: string } }>(cfg, '')).user_info
  return { max: Math.max(1, Number(info?.max_connections) || 1), active: Math.max(0, Number(info?.active_cons) || 0) }
}

/** A stream's URL: a live channel (.ts), a film (/movie/, its container) or an episode (/series/). */
export const streamUrl = (cfg: XtreamConfig, kind: 'live' | 'movie' | 'series', id: number, ext = 'ts') =>
  `${cfg.host}/${kind}/${cfg.user}/${cfg.pass}/${id}.${ext}`

/** The provider's own XMLTV guide. */
export const guideUrl = (cfg: XtreamConfig) => `${cfg.host}/xmltv.php?username=${cfg.user}&password=${cfg.pass}`

// --- Catalogue snapshots -----------------------------------------------------------------------
// The VOD and series lists are ~74 and ~72 MB, and repeated pulls have got the account answered
// with empty lists for hours (2026-10-13). So each is pulled at most once a day into a trimmed
// snapshot in .cache (written atomically) that every reader uses. A stale snapshot is served while
// the next pull runs in the background; a failed or empty pull keeps the last good one and isn't
// retried for an hour. Only a box with no snapshot yet waits for a pull.

const SNAPSHOT_MAX_AGE = 24 * 3600_000
const RETRY_AFTER = 3600_000

interface Category { category_id: string; category_name: string }

function snapshot<T>(name: string, pull: (cfg: XtreamConfig) => Promise<T | null>) {
  const dir = () => process.env.SNAPSHOT_DIR ?? fileURLToPath(new URL('../.cache', import.meta.url))
  const file = jsonFile<{ at: number; data: T } | null>(() => `${dir()}/${name}.json`, () => null)
  let pulling: Promise<void> | null = null
  let tried = 0
  const refresh = (cfg: XtreamConfig) =>
    (pulling ??= (async () => {
      tried = Date.now()
      const data = await pull(cfg).catch(() => null)
      if (data) await file.update(() => ({ at: Date.now(), data })).catch(() => {}) // a background refresh must not throw
    })().finally(() => (pulling = null)))
  return async (cfg: XtreamConfig): Promise<T> => {
    let saved = file.read()
    if (!saved) {
      if (pulling || Date.now() - tried >= RETRY_AFTER) await refresh(cfg)
      saved = file.read(true)
      if (!saved) throw new Error(`no ${name} snapshot yet`)
    } else if (Date.now() - saved.at > SNAPSHOT_MAX_AGE && Date.now() - tried >= RETRY_AFTER) {
      void refresh(cfg)
    }
    return saved.data
  }
}

export interface VodFilm {
  id: number
  name: string
  icon: string
  /** Category id. */
  cat: number
  /** Container extension (mkv, mp4). */
  ext: string
  /** Upload time, epoch seconds. */
  added: number
  /** TMDB id, "" when the provider has none. */
  tmdb: string
}

/** The whole VOD catalogue, adult titles left out: category id to name, and every film. */
export interface VodCatalogue {
  cats: Record<string, string>
  films: VodFilm[]
}

interface VodStream { stream_id: number; name: string; stream_icon?: string; category_id: string; container_extension?: string; added?: string; tmdb?: string | number; is_adult?: string | number }

/** The VOD catalogue, at most a day old. */
export const vodCatalogue = snapshot<VodCatalogue>('vod', async (cfg) => {
  const [vod, cats] = await Promise.all([apiGet<VodStream[]>(cfg, 'get_vod_streams'), apiGet<Category[]>(cfg, 'get_vod_categories')])
  if (!vod?.length || !cats?.length) return null // the provider's empty answer: keep the last good one
  return {
    cats: Object.fromEntries(cats.map((c) => [String(c.category_id), c.category_name])),
    films: vod
      .filter((s) => String(s.is_adult ?? '0') !== '1' && String(s.name ?? '').trim())
      .map((s) => ({
        id: s.stream_id, name: String(s.name).trim(), icon: String(s.stream_icon ?? ''), cat: Number(s.category_id),
        ext: s.container_extension || 'mp4', added: Number(s.added ?? 0), tmdb: String(s.tmdb ?? '').replace(/^0$/, ''),
      })),
  }
})

export interface SeriesEntry { id: number; name: string; cover: string; cat: string; tmdb: string }

/** The series catalogue: categories in the provider's order, and every show. */
export interface SeriesCatalogue {
  cats: { id: string; name: string }[]
  series: SeriesEntry[]
}

interface Series { series_id: number; name: string; cover?: string; category_id: string; tmdb?: string }

/** The series catalogue, at most a day old. */
export const seriesCatalogue = snapshot<SeriesCatalogue>('series', async (cfg) => {
  const [cats, series] = await Promise.all([apiGet<Category[]>(cfg, 'get_series_categories'), apiGet<Series[]>(cfg, 'get_series')])
  if (!series?.length || !cats?.length) return null
  return {
    cats: cats.map((c) => ({ id: String(c.category_id), name: c.category_name })),
    series: series
      .filter((s) => String(s.name ?? '').trim())
      .map((s) => ({ id: s.series_id, name: String(s.name).trim(), cover: s.cover ?? '', cat: String(s.category_id), tmdb: String(s.tmdb ?? '').replace(/^0$/, '') })),
  }
})

// --- Provider names ----------------------------------------------------------------------------

/** A 4K claim: superscript tags read through NFKC (⁴ᴷ ³⁸⁴⁰ᴾ → 4K 3840P, ²¹⁶⁰ᵖ → 2160p), plain 4K/UHD/3840/2160, or Arabic "ultra-HD". */
export const claims4K = (s: string): boolean => /\b4K\b|3840|2160|\bUHD\b|فائقة? الوضوح/i.test(s.normalize('NFKC'))

// Dubbing markets name the audio; subtitling markets keep the original audio and add their
// subtitles; English copies keep the original and claim nothing.
const DUBBED: Record<string, string> = { DE: 'de', ES: 'es', FR: 'fr', IT: 'it', PL: 'pl', RU: 'ru', TR: 'tr', IN: 'hi', PT: 'pt' }
const SUBBED: Record<string, string> = { AR: 'ar', GR: 'el', NL: 'nl', SE: 'sv', IS: 'is', BG: 'bg', AL: 'sq', EX: 'hr', IR: 'fa', KU: 'ku' }
const isLanguage = (t: string) => t === 'EN' || !!DUBBED[t] || !!SUBBED[t]

/** What a language tag ("DE", "ar") claims: a dubbing market's audio or a subtitling market's subtitles (ISO 639-1). */
export function languageClaim(tag: string): { dubbed?: string; subbed?: string } {
  const t = tag.toUpperCase()
  return DUBBED[t] ? { dubbed: DUBBED[t] } : SUBBED[t] ? { subbed: SUBBED[t] } : {}
}

/**
 * The provider's tags before a title, often stacked: "TOP - ", "4K-FR - HDR - ", "EN-TOP - ",
 * "4K -SC- ", "AR-SUBS - ", "4K-D+  -Pita Hall". The dash needs a space on one side, so
 * "WALL-E" stays a title.
 */
const TAG = /^([A-Z0-9+]{1,6}(?:\s?-[A-Z0-9+]{1,6})*)(?:\s+-\s*|\s*-\s+)/

export interface ProviderName {
  /** The title without the provider's tags, list numbering ("110. Amélie") or dotted file-name spelling. */
  title: string
  /** The name's first language tag ("EN", "AR", "DE"), else its category's; "" for none. */
  lang: string
  /** ISO 639-1 audio language the tag claims (a dubbing market's), if any. */
  dubbed?: string
  /** ISO 639-1 subtitle language the tag claims (a subtitling market's), if any. */
  subbed?: string
  /** A camcorder copy ("AR-CAM - …"), filmed in a cinema. */
  cam?: true
}

/** What a provider name means, read with its category ("ES - PELÍCULAS", "NETFLIX MOVIES ⁴ᴷ ³⁸⁴⁰ᴾ"). */
export function readName(raw: string, category = ''): ProviderName {
  let s = raw.trim()
  const tags: string[] = []
  for (let m = s.match(TAG); m; m = s.match(TAG)) {
    tags.push(...m[1].split('-').map((t) => t.trim()))
    s = s.slice(m[0].length).trim()
  }
  s = s.replace(/^4K[\s-]*[A-Z]{2,3}\s+(?=\S)/, '').replace(/^\d{1,3}\.\s*/, '')
  if (!s.includes(' ') && s.includes('.')) s = s.replace(/\.((?:19|20)\d{2})$/, ' ($1)').replace(/\./g, ' ')
  const fromCategory = category.match(/^([A-Z]{2})\b/)?.[1] ?? ''
  // "IN-EN": a section's English copy, its audio English.
  const lang = (tags.includes('EN') ? 'EN' : tags.find(isLanguage)) ?? (isLanguage(fromCategory) ? fromCategory : '')
  const claim = languageClaim(lang)
  // "AR-DUB": a subtitling market's dub has its language for audio.
  return { title: s.replace(/\s+/g, ' ') || raw.trim(), lang, ...(tags.includes('DUB') && claim.subbed ? { dubbed: claim.subbed } : claim), ...(tags.includes('CAM') && { cam: true as const }) }
}

// Decorative Unicode the provider uses for tags (superscript digits + modifier letters:
// ⁴ᴷ ³⁸⁴⁰ᴾ ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ ⱽᴵˢᴵᴼᴺ ᴴᴰᴿ) — stripped for clean names.
const SUPER = /[²³¹⁰-₟ᴬ-ᵪʰ-˿ᶜ-ᶿⱽ]+/g

/** A category's name without its quality tags: "NETFLIX MOVIES ⁴ᴷ ³⁸⁴⁰ᴾ ᴰᵒˡᵇʸ ⱽᶦˢᶦᵒⁿ" -> "NETFLIX MOVIES". */
export function plainCategory(cat: string): string {
  return cat
    .replace(SUPER, ' ')
    .replace(/\b(3840p?|dolby|audio|visi[oó]n|hdr|multi(?:-subs)?)\b/gi, ' ')
    .replace(/فائقة? الوضوح/g, ' ')
    .replace(/(?<=^|\s)[A-ZÀ-Þ](?=\s|$)/g, ' ') // stray leftover capital (e.g. Ó from VISIÓN)
    .replace(/\(\s*\)/g, ' ')
    .replace(/\b4k\b/gi, ' ')
    .replace(/[-–]\s*$/, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}
