// Title ids (KTD10) and the TMDB client (KTD9).
// A title id names a film or show whatever copy plays it: `movie:<tmdb>`, `tv:<tmdb>`, an episode
// `tv:<tmdb>:s<season>e<episode>`. A provider title with no TMDB id keeps its own: `iptv:<vodId>` for a
// film, `iptv:tv:<seriesId>` for a show (VOD and series ids are separate counters, so they could clash);
// those are never merged with anything.
// TMDB details (IMDb id, original language, runtime, cast, recommendations) are fetched only when
// TMDB_TOKEN is set, and kept in .cache/tmdb-titles.json for 30 days; without the token everything
// still answers, with title ids from the provider's tmdb column and no TMDB facts.
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { writeAtomic } from './fs.js'
import { getJson, HttpError } from './http.js'

const tmdbId = (v: unknown) => {
  const s = String(v ?? '').trim()
  return /^[1-9]\d*$/.test(s) ? s : ''
}
export const filmTitle = (tmdb: unknown, vodId: number) => (tmdbId(tmdb) ? `movie:${tmdbId(tmdb)}` : `iptv:${vodId}`)
export const showTitle = (tmdb: unknown, seriesId: number) => (tmdbId(tmdb) ? `tv:${tmdbId(tmdb)}` : `iptv:tv:${seriesId}`)
export const episodeTitle = (show: string, season: number, episode: number) => `${show}:s${season}e${episode}`

export type Kind = 'movie' | 'tv'
/** A TMDB title id's parts (an episode's season and episode too); null for a provider-only (`iptv:`) or malformed id. */
export function parseTitleId(id: string): { kind: Kind; tmdb: string; season?: number; episode?: number } | null {
  const m = id.match(/^(movie|tv):(\d+)(?::s(\d+)e(\d+))?$/)
  if (!m || (m[1] === 'movie' && m[3])) return null
  return { kind: m[1] as Kind, tmdb: m[2], ...(m[3] && { season: +m[3], episode: +m[4] }) }
}
/** A film or show as TMDB knows it, trimmed to what the box serves. */
export interface TmdbTitle {
  /** "tt1375666"; "" when TMDB has none. */
  imdb: string
  /** ISO 639-1, "en". */
  originalLanguage: string
  /** Minutes (a show's: an episode's); 0 when unknown. */
  runtime: number
  name: string
  year: string
  poster: string
  /** The overview; "" when TMDB has none. */
  plot: string
  /** Backdrop path ("/x.jpg"); "" when none. */
  backdrop: string
  genres: string[]
  /** The genres' TMDB ids (films' and shows' differ). */
  genreIds: number[]
  /** TMDB popularity when last fetched. */
  popularity?: number
  /** An adult title: never shown. */
  adult?: true
  /** A show's season numbers (specials left out); [] for a film. */
  seasons: number[]
  cast: { id: number; name: string; character: string }[]
  /** A film's directors, a show's creators. */
  directors: { id: number; name: string }[]
  /** TMDB's own, with its average vote (0–10) and vote count. */
  recommendations: { title: string; name: string; year: string; poster: string; vote?: number; votes?: number }[]
  /** The best YouTube trailer's key ("JE9z-gy4De4"); "" when TMDB lists none. */
  trailer: string
  /** The title in its own language ("Le fabuleux destin d'Amélie Poulain"); "" when TMDB has none. */
  originalTitle: string
  /** A film's franchise as TMDB groups it ("The Lord of the Rings Collection"). */
  collection?: { id: number; name: string }
  /** A show's first network ("HBO"). */
  network?: string
  /** A show's status as TMDB words it: "Returning Series", "Ended", "Canceled", "In Production", "Planned". */
  status?: string
  /** A show's next episode, its air date "2026-11-14". */
  next?: { season: number; episode: number; air: string }
}

interface Raw {
  title?: string
  name?: string
  imdb_id?: string
  original_language?: string
  runtime?: number
  episode_run_time?: number[]
  last_episode_to_air?: { runtime?: number } | null
  release_date?: string
  first_air_date?: string
  poster_path?: string | null
  overview?: string
  backdrop_path?: string | null
  genres?: { id: number; name: string }[]
  popularity?: number
  adult?: boolean
  seasons?: { season_number: number }[]
  created_by?: { id: number; name: string }[]
  external_ids?: { imdb_id?: string | null }
  credits?: { cast?: { id: number; name: string; character?: string }[]; crew?: { id: number; name: string; job?: string }[] }
  recommendations?: { results?: (Raw & { id: number; media_type?: string; vote_average?: number; vote_count?: number })[] }
  videos?: { results?: Video[] }
  original_title?: string
  original_name?: string
  belongs_to_collection?: { id: number; name: string } | null
  networks?: { name: string }[]
  status?: string
  next_episode_to_air?: { season_number: number; episode_number: number; air_date?: string | null } | null
}

interface Video { key: string; site?: string; type?: string; official?: boolean; iso_639_1?: string; published_at?: string }

/** Video languages asked for: English, none, and common original languages (TMDB otherwise lists the request's language only). */
const VIDEO_LANGUAGES = 'en,null,fr,de,es,it,ja,ko,zh,hi,ar,pt,ru,tr,sv,da,no,fi,nl,pl'

/** The best YouTube trailer: official first, then English or the title's own language, then the newest; "" when none. */
export function pickTrailer(videos: Video[], originalLanguage: string): string {
  const rank = (v: Video) => (v.official ? 2 : 0) + (v.iso_639_1 === 'en' || v.iso_639_1 === originalLanguage ? 1 : 0)
  const best = videos
    .filter((v) => v.site === 'YouTube' && v.type === 'Trailer' && v.key)
    .sort((a, b) => rank(b) - rank(a) || (b.published_at ?? '').localeCompare(a.published_at ?? ''))[0]
  return best?.key ?? ''
}

/** TMDB's image host: an image path ("/abc.jpg") is shown at TMDB_IMG + size + path. */
export const TMDB_IMG = 'https://image.tmdb.org/t/p/'
/** A TMDB image path's URL at [size] ("w342"); "" for none. */
export const tmdbImage = (path: string | null | undefined, size: string) => (path ? `${TMDB_IMG}${size}${path}` : '')
/** A TMDB image URL as its path, so the app picks the size; any other URL as is. */
export const imagePath = (url: string) => url.match(/^https?:\/\/image\.tmdb\.org\/t\/p\/[^/]+(\/[^/]+)$/)?.[1] ?? url
const poster = (p: string | null | undefined) => tmdbImage(p, 'w342')
const yearOf = (r: Raw) => (r.release_date || r.first_air_date || '').slice(0, 4)

function trim(kind: Kind, r: Raw): TmdbTitle {
  return {
    imdb: r.imdb_id || r.external_ids?.imdb_id || '',
    originalLanguage: r.original_language ?? '',
    runtime: r.runtime || r.episode_run_time?.[0] || r.last_episode_to_air?.runtime || 0,
    name: r.title ?? r.name ?? '',
    year: yearOf(r),
    poster: poster(r.poster_path),
    plot: r.overview?.trim() ?? '',
    backdrop: r.backdrop_path ?? '',
    genres: (r.genres ?? []).map((g) => g.name),
    genreIds: (r.genres ?? []).map((g) => g.id),
    ...(r.popularity !== undefined && { popularity: r.popularity }),
    ...(r.adult && { adult: true as const }),
    seasons: (r.seasons ?? []).map((x) => x.season_number).filter((n) => n > 0),
    cast: (r.credits?.cast ?? []).slice(0, 10).map(({ id, name, character }) => ({ id, name, character: character ?? '' })),
    directors: kind === 'tv'
      ? (r.created_by ?? []).map(({ id, name }) => ({ id, name }))
      : (r.credits?.crew ?? []).filter((c) => c.job === 'Director').map(({ id, name }) => ({ id, name })),
    recommendations: (r.recommendations?.results ?? []).slice(0, 20).map((x) => ({
      title: (x.media_type ?? kind) === 'tv' ? showTitle(x.id, 0) : filmTitle(x.id, 0),
      name: x.title ?? x.name ?? '',
      year: yearOf(x),
      poster: poster(x.poster_path),
      vote: x.vote_average ?? 0,
      votes: x.vote_count ?? 0,
    })),
    trailer: pickTrailer(r.videos?.results ?? [], r.original_language ?? ''),
    originalTitle: r.original_title ?? r.original_name ?? '',
    ...(r.belongs_to_collection && { collection: { id: r.belongs_to_collection.id, name: r.belongs_to_collection.name } }),
    ...(kind === 'tv' && {
      ...(r.networks?.[0]?.name && { network: r.networks[0].name }),
      ...(r.status && { status: r.status }),
      ...(r.next_episode_to_air?.air_date && { next: { season: r.next_episode_to_air.season_number, episode: r.next_episode_to_air.episode_number, air: r.next_episode_to_air.air_date } }),
    }),
  }
}

const MAX_AGE_MS = 30 * 864e5
const cacheFile = () => process.env.TMDB_CACHE_FILE ?? fileURLToPath(new URL('../.cache/tmdb-titles.json', import.meta.url))
type Entry = { at: number; t?: TmdbTitle | null; e?: TmdbEpisode[]; c?: TmdbPart[] }
let cache: Promise<Record<string, Entry>> | null = null
const loaded = () => (cache ??= readFile(cacheFile(), 'utf8').then((s) => JSON.parse(s) as Record<string, Entry>).catch(() => ({} as Record<string, Entry>)))
const pending = new Map<string, Promise<TmdbTitle | null>>()

const save = (all: Record<string, Entry>) => writeAtomic(cacheFile(), all).catch(() => {})

/** GET a TMDB v3 path ("discover/movie?with_genres=35"). A v3 API key (32 hex) goes in the query; a v4 read token as a Bearer header. */
export function tmdbGet<T>(path: string, key = process.env.TMDB_TOKEN ?? ''): Promise<T> {
  const v3 = /^[0-9a-f]{32}$/i.test(key)
  const url = `https://api.themoviedb.org/3/${path}${v3 ? `${path.includes('?') ? '&' : '?'}api_key=${key}` : ''}`
  return getJson<T>(url, v3 ? {} : { headers: { Authorization: `Bearer ${key}` } })
}

/**
 * A film's or show's TMDB details; null without TMDB_TOKEN, for an id TMDB doesn't know, or when TMDB
 * fails with nothing cached (a stale entry is served instead).
 */
export async function tmdbTitle(kind: Kind, id: string | number): Promise<TmdbTitle | null> {
  const key = process.env.TMDB_TOKEN
  const tmdb = tmdbId(id)
  if (!key || !tmdb) return null
  const k = `${kind}:${tmdb}`
  const all = await loaded()
  // An entry kept before plots and backdrops, recommendations' votes, trailers, original titles, or genre ids were is fetched again;
  // a running show's daily, as its next episode moves.
  const kept = all[k]?.t
  const maxAge = kept?.status === 'Returning Series' ? 864e5 : MAX_AGE_MS
  if (all[k] && Date.now() - all[k].at < maxAge && (!kept || (kept.plot !== undefined && (kept.recommendations[0] ?? { votes: 0 }).votes !== undefined && kept.trailer !== undefined && kept.originalTitle !== undefined && kept.genreIds !== undefined))) return kept ?? null
  let job = pending.get(k)
  if (!job) {
    job = tmdbGet<Raw>(`${kind}/${tmdb}?append_to_response=external_ids,credits,recommendations,videos&include_video_language=${VIDEO_LANGUAGES}`, key)
      .then((r) => trim(kind, r))
      .catch((e) => {
        if (e instanceof HttpError && e.status === 404) return null
        throw e
      })
      .then((t) => {
        all[k] = { at: Date.now(), t }
        save(all)
        return t
      }, () => all[k]?.t ?? null)
      .finally(() => pending.delete(k))
    pending.set(k, job)
  }
  return job
}

/** A film of a TMDB collection, as far as a card needs it. */
export interface TmdbPart { id: number; title?: string; release_date?: string; poster_path?: string | null; backdrop_path?: string | null; original_language?: string; genre_ids?: number[] }

/** A TMDB collection's films with a release date, oldest first, kept 30 days in the same cache; [] when TMDB fails with nothing kept. */
export async function tmdbCollection(id: number): Promise<TmdbPart[]> {
  const key = process.env.TMDB_TOKEN
  if (!key) return []
  const k = `collection:${id}`
  const all = await loaded()
  if (all[k] && Date.now() - all[k].at < MAX_AGE_MS) return all[k].c ?? []
  try {
    const r = await tmdbGet<{ parts?: TmdbPart[] }>(`collection/${id}`, key)
    const c = (r.parts ?? [])
      .filter((p) => p.release_date)
      .sort((a, b) => a.release_date!.localeCompare(b.release_date!))
      .map(({ id, title, release_date, poster_path, backdrop_path, original_language, genre_ids }) => ({ id, title, release_date, poster_path, backdrop_path, original_language, genre_ids }))
    all[k] = { at: Date.now(), c }
    save(all)
    return c
  } catch {
    return all[k]?.c ?? []
  }
}

/** Runtimes in minutes (a show's: an episode's) from the kept details, whatever their age, by title id; never asks TMDB. */
export const keptRuntimes = async () => {
  const all = await loaded()
  return (id: string) => all[id]?.t?.runtime || undefined
}

/** [id]'s runtime: the kept one, else TMDB's details asked now; 0 when unknown. Nightly. */
export async function runtimeOf(id: string): Promise<number> {
  const p = parseTitleId(id)
  if (!p || p.season != null) return 0
  return (await keptRuntimes())(id) || (await tmdbTitle(p.kind, p.tmdb))?.runtime || 0
}

/** A title id's IMDb id and original language, to spread into a response; {} when TMDB has nothing. */
export async function tmdbFacts(title: string): Promise<{ imdb?: string; originalLanguage?: string }> {
  const p = parseTitleId(title)
  const t = p ? await tmdbTitle(p.kind, p.tmdb) : null
  return t ? { ...(t.imdb && { imdb: t.imdb }), ...(t.originalLanguage && { originalLanguage: t.originalLanguage }) } : {}
}

/** A show's episode as TMDB lists it. */
export interface TmdbEpisode { season: number; episode: number; title: string; secs: number; image: string; /** Air date, "2026-11-04". */ air: string }
interface RawEpisode { season_number: number; episode_number: number; name?: string; runtime?: number | null; still_path?: string | null; air_date?: string | null }

const EPISODES_MAX_AGE_MS = 864e5

/**
 * A show's aired episodes in order ([seasons] asked 20 to a call, and the season after them, so a new season
 * shows before the 30-day details know it), kept a day in the same cache, as new episodes air; [] without
 * TMDB_TOKEN, the last list kept when TMDB fails.
 */
export async function tmdbEpisodes(id: string | number, seasons: number[]): Promise<TmdbEpisode[]> {
  const key = process.env.TMDB_TOKEN
  const tmdb = tmdbId(id)
  if (!key || !tmdb || !seasons.length) return []
  const k = `tv:${tmdb}:episodes`
  const all = await loaded()
  // A list kept before episodes had air dates is fetched again.
  if (all[k] && Date.now() - all[k].at < EPISODES_MAX_AGE_MS && (!all[k].e?.length || all[k].e![0].air)) return all[k].e ?? []
  const today = new Date().toISOString().slice(0, 10)
  const asked = [...seasons, Math.max(...seasons) + 1] // TMDB leaves out a season it doesn't have
  const chunks = Array.from({ length: Math.ceil(asked.length / 20) }, (_, i) => asked.slice(i * 20, i * 20 + 20))
  try {
    const parts = await Promise.all(chunks.map((c) => tmdbGet<Record<string, { episodes?: RawEpisode[] }>>(`tv/${tmdb}?append_to_response=${c.map((n) => `season/${n}`).join(',')}`, key)))
    const e = parts
      .flatMap((p) => Object.entries(p).filter(([n]) => n.startsWith('season/')).flatMap(([, s]) => s?.episodes ?? []))
      .filter((x) => x.air_date && x.air_date <= today)
      .map((x) => ({ season: x.season_number, episode: x.episode_number, title: x.name ?? '', secs: (x.runtime ?? 0) * 60, image: tmdbImage(x.still_path, 'w300'), air: x.air_date! }))
      .sort((a, b) => a.season - b.season || a.episode - b.episode)
    all[k] = { at: Date.now(), e }
    save(all)
    return e
  } catch {
    return all[k]?.e ?? []
  }
}
