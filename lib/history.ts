// The owner's history (U16; R26, R31, KTD19): what Trakt and the Letterboxd diary say was watched, Trakt's
// Watchlist, and each followed show's next episode for Up next. The Trakt and Letterboxd
// parts are kept in .cache/history.json, read again nightly by scripts/build-library.ts and when the app asks
// and they are over 10 minutes old (then one Trakt call, last_activities, when nothing changed, the RSS
// feed and the Letterboxd watchlist's pages; the owner's Letterboxd export adds older marks and stars). /api/app/history serves them with titles in the library's shape, with ETag/304 like the library.
// The owner's IMDb ratings (.cache/imdb-ratings.json, dropped in by hand) are read on each refresh: every title
// rated there counts as watched. Watched marks are the union of Trakt, Letterboxd, IMDb and the stick's own marks
// (merged on the stick); the stick's finished plays, marks and ratings go to Trakt through the box (trakt.ts).
// A source that fails keeps what it gave last and says so in `src`.
import { readFile } from 'node:fs/promises'
import { pendingLogs } from './letterboxd-log.js'
import { setTimeout as sleep } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { jsonFile, writeAtomic } from './fs.js'
import { pool, ROW_CAP } from './library.js'
import { decorate, fromDetails, type Known, type LibTitle } from './titles.js'
import { episodeTitle, parseTitleId, tmdbEpisodes, tmdbTitle, type Kind, type TmdbEpisode } from './tmdb.js'
import { accessToken, revoked, status, traktHeaders } from './trakt.js'

const API = 'https://api.trakt.tv'
export const HISTORY_FILE = fileURLToPath(new URL('../.cache/history.json', import.meta.url))

/** What the box keeps of the owner's history: title ids (KTD10). */
export interface Kept {
  at: number
  trakt: 'on' | 'off' | 'reconnect' | 'error'
  letterboxd: 'on' | 'off' | 'blocked'
  /** Trakt's last_activities.all when its parts were read. */
  acts?: string
  movies: string[]
  /** Each watched film's last watch, epoch ms; 0 where only the day it came out is known (a film logged without a date). */
  filmAt?: Record<string, number>
  /** Films watched more than once: how many times. */
  plays?: Record<string, number>
  /** Watched episodes, the most recently watched first (U17 reads the owner's latest from here). */
  eps: string[]
  /** Each watched show's last watch, epoch ms. */
  shows: Record<string, number>
  /** Shows hidden from Trakt's progress (dropped): never followed. */
  hidden: string[]
  watchlist: string[]
  /** Films in the Letterboxd diary: every one the feed or the export has listed (the feed holds the latest 50). */
  lb: string[]
  /** The owner's Letterboxd stars per film (0.5–5): the export's, the feed's newer ones on top. */
  ratings?: Record<string, number>
  /** The Letterboxd watchlist, in its order (newest first). */
  lbw?: string[]
  /** The owner's Trakt ratings (1–10) of films, shows and episodes, the latest first. */
  rated?: Record<string, number>
  /** The owner's IMDb ratings (1–10). */
  imdb?: Record<string, number>
  /** Watchlist titles' runtimes in minutes (a show's episode), from TMDB nightly ([watchlistRuntimes]). */
  runtimes?: Record<string, number>
  /** The stick's marks and watchlist changes of the last hour, which a refresh begun before one landed replays ([keepRefreshed]). */
  edits?: Edit[]
}

/** A mark (POST /api/app/mark: [covered], watched or not, a rating set or cleared) or a watchlist change ([on], with the title's runtime). */
export interface Edit { at: number; title: string; covered?: string[]; watched?: boolean; rating?: number | null; on?: boolean; runtime?: number }
type TraktPart = Pick<Kept, 'trakt' | 'acts' | 'movies' | 'filmAt' | 'plays' | 'eps' | 'shows' | 'hidden' | 'watchlist' | 'rated'>

const NONE = { movies: [], filmAt: {}, plays: {}, eps: [], shows: {}, hidden: [], watchlist: [], rated: {} }
const traktOf = (h: Kept | null) => ({ ...NONE, ...(h && { acts: h.acts, movies: h.movies, filmAt: h.filmAt ?? {}, plays: h.plays ?? {}, eps: h.eps, shows: h.shows, hidden: h.hidden, watchlist: h.watchlist, rated: h.rated ?? {} }) })

type Ids = { ids?: { tmdb?: number | null } }
const film = (m?: Ids) => (m?.ids?.tmdb ? `movie:${m.ids.tmdb}` : null)
const show = (s?: Ids) => (s?.ids?.tmdb ? `tv:${s.ids.tmdb}` : null)
const known = (x: string | null): x is string => !!x

class Refused extends Error {}

async function readTrakt(access: string, prev: Kept | null, full: boolean): Promise<TraktPart> {
  const page = async (path: string) => {
    const r = await fetch(API + path, { headers: traktHeaders(access), signal: AbortSignal.timeout(20_000) })
    if (r.status === 401) throw new Refused()
    if (!r.ok) throw new Error(`Trakt ${r.status} on ${path}`)
    return { body: await r.json(), pages: Number(r.headers.get('x-pagination-page-count') ?? 1) }
  }
  // Every page: Trakt answers lists 100 at a time (250 at most), so the first page alone held 100 of 756 watched films.
  const get = async <T>(path: string): Promise<T> => {
    const sep = path.includes('?') ? '&' : '?'
    const first = await page(path.includes('limit=') ? path : `${path}${sep}limit=250`)
    if (!Array.isArray(first.body) || first.pages <= 1) return first.body as T
    const rest = []
    for (let p = 2; p <= first.pages; p++) rest.push(...(await page(`${path}${sep}page=${p}${path.includes('limit=') ? '' : '&limit=250'}`)).body)
    return [...first.body, ...rest] as T
  }
  const acts = (await get<{ all?: string }>('/sync/last_activities')).all
  if (!full && prev?.trakt === 'on' && acts && prev.acts === acts && prev.rated && prev.filmAt) return { ...traktOf(prev), trakt: 'on' }
  const [movies, shows, hidden, watchlist, ratings] = await Promise.all([
    get<{ movie: Ids; plays: number; last_watched_at: string }[]>('/sync/watched/movies'),
    get<{ last_watched_at: string; show: Ids; seasons?: { number: number; episodes: { number: number; last_watched_at: string }[] }[] }[]>('/sync/watched/shows'),
    get<{ show?: Ids }[]>('/users/hidden/progress_watched?type=show&limit=1000'),
    get<{ type: string; movie?: Ids; show?: Ids }[]>('/sync/watchlist'),
    get<{ rating: number; type: string; movie?: Ids; show?: Ids; episode?: { season: number; number: number } }[]>('/sync/ratings'),
  ])
  const eps = shows.flatMap((s) => {
    const id = show(s.show)
    return id ? (s.seasons ?? []).flatMap((n) => n.episodes.map((e) => ({ id: episodeTitle(id, n.number, e.number), at: Date.parse(e.last_watched_at) }))) : []
  })
  return {
    trakt: 'on',
    acts,
    movies: movies.map((m) => film(m.movie)).filter(known),
    // A watch at midnight UTC on the dot is a film logged by its release day (the 2026-11-07 Letterboxd/IMDb backfill), not a time.
    filmAt: Object.fromEntries(movies.flatMap((m) => (film(m.movie) ? [[film(m.movie)!, m.last_watched_at.endsWith('T00:00:00.000Z') ? 0 : Date.parse(m.last_watched_at)]] : []))),
    plays: Object.fromEntries(movies.flatMap((m) => (film(m.movie) && m.plays > 1 ? [[film(m.movie)!, m.plays]] : []))),
    eps: eps.sort((a, b) => b.at - a.at).map((e) => e.id),
    shows: Object.fromEntries(shows.map((s) => [show(s.show), Date.parse(s.last_watched_at)]).filter(([id]) => id)),
    hidden: hidden.map((h) => show(h.show)).filter(known),
    watchlist: watchlist.map((w) => (w.type === 'movie' ? film(w.movie) : w.type === 'show' ? show(w.show) : null)).filter(known),
    // Seasons' ratings are left out: nothing here shows one.
    rated: Object.fromEntries(ratings.flatMap((r) => {
      const id = r.type === 'movie' ? film(r.movie) : r.type === 'show' ? show(r.show) : r.type === 'episode' && r.episode && show(r.show) ? episodeTitle(show(r.show)!, r.episode.season, r.episode.number) : null
      return id ? [[id, r.rating]] : []
    })),
  }
}

async function traktPart(prev: Kept | null, full: boolean): Promise<TraktPart> {
  const access = await accessToken()
  if (!access) {
    const state = (await status()).state
    // Signed out: nothing of Trakt's is kept. A sign-in that needs renewing keeps what it read last.
    return state === 'on' || state === 'reconnect' ? { ...traktOf(prev), trakt: 'reconnect' } : { ...NONE, trakt: 'off' }
  }
  try {
    return await readTrakt(access, prev, full)
  } catch (e) {
    if (e instanceof Refused) revoked()
    return { ...traktOf(prev), trakt: e instanceof Refused ? 'reconnect' : 'error' }
  }
}

/** The films a Letterboxd RSS feed lists (diary entries and reviews carry `tmdb:movieId`). */
export const letterboxdFilms = (xml: string) => [...xml.matchAll(/<tmdb:movieId>(\d+)<\/tmdb:movieId>/g)].map((m) => `movie:${m[1]}`)

/** The owner's stars per film in a Letterboxd RSS feed, the latest entry's. */
export const letterboxdRatings = (xml: string): Record<string, number> => Object.fromEntries(xml.split('<item>').slice(1).reverse().flatMap((i) => {
  const id = i.match(/<tmdb:movieId>(\d+)</)?.[1], r = i.match(/<letterboxd:memberRating>([\d.]+)</)?.[1]
  return id && r ? [[`movie:${id}`, Number(r)]] : []
}))

const cacheFile = (name: string) => fileURLToPath(new URL(`../.cache/${name}`, import.meta.url))
// The owner's Letterboxd export, dropped in by hand: { at, watched: ["movie:N"], ratings: { "movie:N": 0.5–5 }, watchlist: ["movie:N"] }.
const LB_EXPORT = cacheFile('letterboxd-export.json')
// The owner's IMDb ratings, dropped in by hand: { at, ratings: { "movie:N" | "tv:N" | "tv:N:sSeE": 1–10 } }.
const IMDB = cacheFile('imdb-ratings.json')
/** Letterboxd film slug → title id: each film's page is read once. */
const LB_IDS = cacheFile('letterboxd-tmdb.json')
const readJson = <T>(file: string, none: T): Promise<T> => readFile(file, 'utf8').then((s) => JSON.parse(s) as T, () => none)

const BROWSER_UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36'
const letterboxd = (path: string) =>
  fetch(`https://letterboxd.com${path}`, { headers: { 'user-agent': BROWSER_UA }, signal: AbortSignal.timeout(15_000) }).then((r) => (r.ok ? r.text() : ''), () => '')

/** The Letterboxd watchlist as title ids; null when a page didn't answer. Up to 20 new films' pages read per call, 2 a second. */
async function letterboxdWatchlist(user: string): Promise<string[] | null> {
  const slugs: string[] = []
  for (let n = 1; n <= 30; n++) {
    const html = await letterboxd(`/${user}/watchlist/${n > 1 ? `page/${n}/` : ''}`)
    if (!html.includes('Watchlist &bull;')) return null
    slugs.push(...[...html.matchAll(/data-item-slug="([^"]+)"/g)].map((m) => m[1]))
    if (!html.includes(`/watchlist/page/${n + 1}/`)) break
  }
  const ids = await readJson<Record<string, string>>(LB_IDS, {})
  let looked = 0
  for (const slug of slugs) {
    if (ids[slug] || looked >= 20) continue
    if (looked++) await sleep(500)
    const page = await letterboxd(`/film/${slug}/`)
    const tmdb = page.match(/data-tmdb-id="(\d+)"/)?.[1]
    if (tmdb) ids[slug] = `${page.includes('data-tmdb-type="tv"') ? 'tv' : 'movie'}:${tmdb}`
  }
  if (looked) await writeAtomic(LB_IDS, ids)
  return [...new Set(slugs.map((s) => ids[s]).filter(known))]
}

async function letterboxdPart(prev: Kept | null): Promise<Pick<Kept, 'letterboxd' | 'lb' | 'ratings' | 'lbw'>> {
  const user = process.env.LETTERBOXD_USER
  if (!user) return { letterboxd: 'off', lb: [], ratings: {}, lbw: [] }
  const u = encodeURIComponent(user)
  const [xml, lbw, exp] = await Promise.all([
    letterboxd(`/${u}/rss/`),
    letterboxdWatchlist(u),
    readJson<{ watched?: string[]; ratings?: Record<string, number>; watchlist?: string[] }>(LB_EXPORT, {}),
  ])
  // A challenge page or an error: skipped, what was read before kept.
  const rss = xml.includes('<rss')
  return {
    letterboxd: rss && lbw ? 'on' : 'blocked',
    lb: [...new Set([...(rss ? letterboxdFilms(xml) : []), ...(prev?.lb ?? []), ...(exp.watched ?? [])])],
    ratings: { ...prev?.ratings, ...exp.ratings, ...(rss && letterboxdRatings(xml)) },
    lbw: lbw ?? prev?.lbw ?? exp.watchlist ?? [],
  }
}

/**
 * The history read again from Trakt and Letterboxd. Trakt's parts are read whole only when its last activity
 * changed, or [full] (nightly).
 */
export async function refreshHistory(prev: Kept | null, { full = false } = {}): Promise<Kept> {
  const [trakt, lb, imdb] = await Promise.all([traktPart(prev, full), letterboxdPart(prev), readJson<{ ratings?: Record<string, number> }>(IMDB, {})])
  return { at: Date.now(), ...trakt, ...lb, imdb: imdb.ratings ?? prev?.imdb ?? {}, ...(prev && { runtimes: prev.runtimes }) }
}

// The server and the nightly build both write it, so every change goes through update() (fs.ts).
const store = jsonFile<Kept | null>(() => process.env.HISTORY_FILE ?? HISTORY_FILE, () => null)

/** The kept history as it is on disk now; null before the first refresh. */
export const loadHistory = async () => store.read(true)

/** Applies [change] to the kept history as it is on disk now, under the file's lock. */
export const updateHistory = (change: (h: Kept | null) => Kept | null) => store.update(change)

const EDITS_MS = 3600e3
// Trakt's own reads may lag a write a little, so a refresh replays the edits from a minute before it began.
const LAG_MS = 60e3
const recent = (edits: Edit[] = [], now = Date.now()) => edits.filter((e) => now - e.at < EDITS_MS)
const applyEdit = (h: Kept, e: Edit): Kept => (e.on !== undefined ? withWatchlist(h, e.title, e.on, e.runtime) : withMark(h, e.title, e.covered ?? [e.title], e.watched, e.rating))

/** Applies a mark or watchlist change the stick made to the kept history, and notes it for refreshes under way; nothing before the first refresh. */
export const editHistory = (edit: Omit<Edit, 'at'>) => {
  const e = { ...edit, at: Date.now() }
  return store.update((h) => h && { ...applyEdit(h, e), edits: [...recent(h.edits), e] })
}

/**
 * Keeps [next], a refresh begun at [started], as the history: with the edits made since it began
 * replayed onto it (what it read may predate them) and the runtimes the nightly build kept meanwhile.
 */
export const keepRefreshed = (next: Kept, started: number) =>
  store.update((cur) => {
    const edits = recent(cur?.edits)
    return edits.filter((e) => e.at >= started - LAG_MS).reduce(applyEdit, { ...next, runtimes: cur?.runtimes ?? next.runtimes, edits })
  }) as Promise<Kept>

const FRESH_MS = 10 * 60e3
let reading: Promise<Kept | null> | null = null

/** The kept history, read again first when it is over 10 minutes old (one read at a time). */
export function currentHistory(): Promise<Kept | null> {
  return (reading ??= (async () => {
    const prev = await loadHistory()
    if (prev && Date.now() - prev.at < FRESH_MS) return prev
    const started = Date.now()
    return keepRefreshed(await refreshHistory(prev), started)
  })().finally(() => (reading = null)))
}

/** A followed show: when it was last watched and the furthest episode watched. */
export interface Followed { last: number; season: number; episode: number }

const FOLLOW_MS = 183 * 864e5
/** An episode id's show, season and episode; null for anything else. */
const episodeOf = (id = '') => {
  const p = parseTitleId(id)
  return p?.kind === 'tv' && p.season != null ? ([`tv:${p.tmdb}`, p.season, p.episode!] as const) : null
}

/**
 * Shows the owner follows: watched on Trakt in the last 6 months (unless hidden from its progress), or on the
 * stick ([local]: "tv:N:sSeE@epoch ms", the episode it last finished), each with its furthest watched episode.
 */
export function followed(h: Kept | null, local: string[], now = Date.now()): Map<string, Followed> {
  const out = new Map<string, Followed>()
  const further = (id: string, season: number, episode: number, last = 0) => {
    const f = out.get(id)
    if (!f) return
    if (season > f.season || (season === f.season && episode > f.episode)) Object.assign(f, { season, episode })
    f.last = Math.max(f.last, last)
  }
  const hidden = new Set(h?.hidden)
  for (const [id, last] of Object.entries(h?.shows ?? {})) if (now - last < FOLLOW_MS && !hidden.has(id)) out.set(id, { last, season: 0, episode: 0 })
  for (const l of local) {
    const [, ep, at] = l.match(/^(.+)@(\d+)$/) ?? []
    const m = episodeOf(ep)
    if (!m || now - Number(at) >= FOLLOW_MS) continue
    if (!out.has(m[0])) out.set(m[0], { last: 0, season: 0, episode: 0 })
    further(...m, Number(at))
  }
  for (const e of h?.eps ?? []) {
    const m = episodeOf(e)
    if (m) further(...m)
  }
  return out
}

/** A followed show's next episode for Up next; [new] when it aired after the show was last watched. */
export interface Next { show: string; ep: string; c: string; en?: string; air: string; new?: 1 }

/** Each followed show's first aired episode after its furthest watched (none when caught up), the latest aired first. */
export async function nextEpisodes(follow: Map<string, Followed>, episodes: (show: string) => Promise<TmdbEpisode[]>): Promise<Next[]> {
  const next = await Promise.all([...follow].map(async ([id, f]) => {
    const e = (await episodes(id)).find((x) => x.season > f.season || (x.season === f.season && x.episode > f.episode))
    return e && { show: id, ep: episodeTitle(id, e.season, e.episode), c: `S${e.season} E${e.episode}`, ...(e.title && { en: e.title }), air: e.air, ...(Date.parse(e.air) > f.last && { new: 1 as const }) }
  }))
  return next.filter((n): n is Next => !!n).sort((a, b) => b.air.localeCompare(a.air))
}

const FOLLOW_CAP = 30
/** Titles the Watchlist can show, which the nightly build scores whatever the library rows hold. */
export const historyTitles = (h: Kept | null) => watchlistOf(h).slice(0, ROW_CAP * 2)
/** The Letterboxd watchlist, less the films taken off it on the TV that the owner's browser hasn't taken off Letterboxd yet. */
const lbwOf = (h: Kept | null) => {
  const leaving = new Set(pendingLogs().filter((l) => l.watchlist === false).map((l) => `movie:${l.tmdb}`))
  return (h?.lbw ?? []).filter((id) => !leaving.has(id))
}

/** The Watchlist: Trakt's, then Letterboxd's. */
export const watchlistOf = (h: Kept | null) => [...new Set([...(h?.watchlist ?? []), ...lbwOf(h)])]

/** The owner's rating of each title out of 10: Trakt's, else IMDb's, else Letterboxd's stars doubled; Trakt's latest first. */
export function yours(h: Kept | null): Map<string, number> {
  const out = new Map<string, number>()
  for (const [id, r] of [...Object.entries(h?.rated ?? {}), ...Object.entries(h?.imdb ?? {}), ...Object.entries(h?.ratings ?? {}).map(([id, r]) => [id, r * 2] as const)]) {
    if (!out.has(id)) out.set(id, r)
  }
  return out
}

/** Each Watchlist title's runtime in minutes, as TMDB's kept details give it (looked up when missing); 0 unknown. Nightly. */
export async function watchlistRuntimes(h: Kept): Promise<Record<string, number>> {
  return Object.fromEntries(await pool(watchlistOf(h), 4, async (id) => {
    const [kind, tmdb] = id.split(':') as [Kind, string]
    return [id, h.runtimes?.[id] || ((await tmdbTitle(kind, tmdb))?.runtime ?? 0)] as const
  }))
}

/** [ids] shortest runtime first ([Kept.runtimes], else the library's for titles added since the nightly kept theirs); unknown ones last, in their order. */
const byRuntime = (h: Kept | null, k: Known, ids: string[]) => {
  const rt = (id: string) => h?.runtimes?.[id] || k.titles.get(id)?.rt || k.rt?.(id) || 1e9
  return ids.slice().sort((a, b) => rt(a) - rt(b))
}

/** [h] with [title] put on the Watchlist first ([on], its runtime kept so the row, shortest first, places it at once) or taken off. */
export const withWatchlist = (h: Kept, title: string, on: boolean, runtime?: number): Kept =>
  ({ ...h, watchlist: [...(on ? [title] : []), ...h.watchlist.filter((t) => t !== title)], ...(runtime && { runtimes: { ...h.runtimes, [title]: runtime } }) })

/** [h] with a mark the stick wrote to Trakt (POST /api/app/mark): [covered], its films or episodes, watched or not; its Trakt rating set or cleared. */
export function withMark(h: Kept, title: string, covered: string[], watched?: boolean, rating?: number | null): Kept {
  const out = { ...h }
  if (watched != null) {
    const key = title.startsWith('movie:') ? 'movies' : 'eps'
    const ids = new Set(covered)
    out[key] = [...(watched ? covered : []), ...h[key].filter((x) => !ids.has(x))]
    if (key === 'movies') out.filmAt = { ...Object.fromEntries(Object.entries(h.filmAt ?? {}).filter(([id]) => !ids.has(id))), ...(watched && Object.fromEntries(covered.map((id) => [id, Date.now()]))) }
  }
  if (rating !== undefined) {
    const rest = Object.fromEntries(Object.entries(h.rated ?? {}).filter(([id]) => id !== title))
    out.rated = rating ? { [title]: rating, ...rest } : rest
  }
  return out
}

/** [historyAnswer]'s seen: films by their last watch and plays, shows by their last episode's watch. */
export const seenOf = (h: Kept | null): Record<string, [number, number]> => ({
  ...Object.fromEntries(Object.entries(h?.filmAt ?? {}).map(([id, at]) => [id, [Math.round(at / 1000), h?.plays?.[id] ?? 1]])),
  ...Object.fromEntries(Object.entries(h?.shows ?? {}).map(([id, at]) => [id, [Math.round(at / 1000), 1]])),
})

/** Every title the owner watched: Trakt's films, episodes and shows (one episode is enough), Letterboxd's diary, and anything rated on Trakt, Letterboxd or IMDb. */
export const watchedOf = (h: Kept | null) =>
  new Set([...(h?.movies ?? []), ...(h?.lb ?? []), ...(h?.eps ?? []), ...Object.keys(h?.shows ?? {}), ...Object.keys(h?.rated ?? {}), ...Object.keys(h?.ratings ?? {}), ...Object.keys(h?.imdb ?? {})])

/**
 * What /api/app/history answers: each source's state, every title watched ([watchedOf]), the
 * owner's ratings, the Watchlist (shortest first, then Trakt's order) as library titles, and the next episode of each followed show ([local] from the stick, see [followed]).
 */
export async function historyAnswer(h: Kept | null, local: string[], k: Known) {
  const titles = new Map<string, LibTitle | null>()
  const title = async (id: string): Promise<LibTitle | null> => {
    if (titles.has(id)) return titles.get(id)!
    const [kind, tmdb] = id.split(':') as [Kind, string]
    const t = k.titles.has(id) ? null : await tmdbTitle(kind, tmdb)
    const base = k.titles.get(id) ?? (t && fromDetails(id, t))
    const out = base ? decorate(k, { ...base, ...(h?.runtimes?.[id] && { rt: h.runtimes[id] }) }) : null
    titles.set(id, out)
    return out
  }
  const follow = new Map([...followed(h, local)].sort(([, a], [, b]) => b.last - a.last).slice(0, FOLLOW_CAP))
  const next = await nextEpisodes(follow, async (id) => {
    const t = await tmdbTitle('tv', id.slice(3))
    return t ? tmdbEpisodes(id.slice(3), t.seasons) : []
  })
  const list = async (ids: string[]) => (await pool(ids, 8, title)).filter((t): t is LibTitle => !!t)
  const [watchlist, shows] = await Promise.all([
    list(byRuntime(h, k, watchlistOf(h)).slice(0, ROW_CAP * 2)),
    pool(next, 8, (n) => title(n.show)),
  ])
  return {
    src: { trakt: h?.trakt ?? 'off', letterboxd: h?.letterboxd ?? 'off' },
    watched: [...watchedOf(h)],
    watchlist,
    // The owner's rating per title, out of 10 (see [yours]).
    ratings: Object.fromEntries(yours(h)),
    // When each title was last watched and how often, where Trakt knows: [epoch seconds (0: watched, day unknown), plays].
    seen: seenOf(h),
    next: next.flatMap(({ show: _s, ...n }, i) => (shows[i] ? [{ t: shows[i], ...n }] : [])),
  }
}
