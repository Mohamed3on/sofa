// Library titles: the card every film and show is served as (library rows, Up next, the Watchlist, search, title
// and person pages), every conversion into it, and the enrichment every card gets ([decorate]: the tier the
// library knows for it, its score and its kept runtime, from [Known]).
import type { Library } from './_library.js'
import { scoreTitles, type Score, type ScoreCache, type Store } from './_scores.js'
import { filmTitle, imagePath, parseTitleId, showTitle, tmdbTitle, type Kind, type TmdbTitle } from './_tmdb.js'
import type { Tier } from './_quality-labels.js'
import type { VodItem } from './_vod.js'

/** A film or show (a person in search: [k] 'p'); empty fields are left out. Its score fields are [Score]'s. */
export interface LibTitle extends Score {
  /** Title id (see _tmdb.ts); `person:<tmdb>` for a person. */
  id: string
  /** 'm' a film, 't' a show, 'p' a person (search only). */
  k: 'm' | 't' | 'p'
  n: string
  y?: number
  /** Poster: a TMDB path ("/abc.jpg", shown at img + size + path) or a full URL (the provider's own). */
  p?: string
  /** Backdrop, a TMDB path. */
  b?: string
  /** Original language, ISO 639-1. */
  l?: string
  /** TMDB genre ids (films' and shows' differ; names in Library.gn). */
  g?: number[]
  /** The best picture tier of any copy seen; absent when none is known. */
  t?: Tier
  /** 1 when [t] is a category's claim (its resolution's ceiling), not measured. */
  e?: 1
  /** A film the provider has: its best copy's VOD id and container, which Play starts when the box can't answer (KTD13). */
  v?: number
  x?: string
  /** Runtime in minutes (a show's: an episode's), from TMDB's kept details; absent when unknown. */
  rt?: number
  /** TMDB popularity: picks a row's candidates without a score in the build (library-full.json); not served. */
  pp?: number
}

/** A TMDB list result or details, as far as a card needs it. */
export interface TmdbItem {
  id: number
  title?: string
  name?: string
  release_date?: string
  first_air_date?: string
  poster_path?: string | null
  backdrop_path?: string | null
  original_language?: string
  genre_ids?: number[]
  genres?: { id: number }[]
  media_type?: string
  adult?: boolean
  popularity?: number
}

const year = (y: string | undefined) => Number(y?.slice(0, 4)) || undefined

/** A TMDB list result (or a title's or a collection part's raw details). */
export function fromTmdb(kind: Kind, r: TmdbItem): LibTitle {
  const g = r.genre_ids ?? r.genres?.map((x) => x.id) ?? []
  return {
    id: kind === 'tv' ? showTitle(r.id, 0) : filmTitle(r.id, 0),
    k: kind === 'tv' ? 't' : 'm',
    n: r.title ?? r.name ?? '',
    y: year(r.release_date || r.first_air_date),
    p: r.poster_path || undefined,
    b: r.backdrop_path || undefined,
    l: r.original_language || undefined,
    g: g.length ? g : undefined,
    pp: r.popularity,
  }
}

/**
 * A film's or show's card as a TMDB list result gives it, from its details (TMDB's disk cache, _tmdb.ts [tmdbTitle]):
 * how the library looks up a title no list brought; null for one TMDB doesn't know, an adult one, or an episode.
 */
export async function titleCard(id: string): Promise<LibTitle | null> {
  const p = parseTitleId(id)
  const t = p && p.season == null ? await tmdbTitle(p.kind, p.tmdb) : null
  if (!p || !t || t.adult) return null
  return fromTmdb(p.kind, {
    id: Number(p.tmdb), title: t.name, release_date: t.year, poster_path: imagePath(t.poster) || null, backdrop_path: t.backdrop || null,
    original_language: t.originalLanguage, genre_ids: t.genreIds, popularity: t.popularity,
  })
}

const kindOf = (id: string) => (id.startsWith('tv:') ? 't' as const : 'm' as const)

/** A title from its kept TMDB details ([tmdbTitle]), for one the library doesn't hold. */
export const fromDetails = (id: string, t: TmdbTitle): LibTitle => ({
  id, k: kindOf(id), n: t.name, ...(year(t.year) && { y: year(t.year) }), ...(t.poster && { p: t.poster }), ...(t.backdrop && { b: t.backdrop }), ...(t.originalLanguage && { l: t.originalLanguage }),
})

/** One of a title page's More like this ([TmdbTitle.recommendations]). */
export const fromRecommendation = (r: Pick<TmdbTitle['recommendations'][number], 'title' | 'name' | 'year' | 'poster'>): LibTitle => ({
  id: r.title, k: kindOf(r.title), n: r.name, ...(year(r.year) && { y: year(r.year) }), ...(r.poster && { p: r.poster }),
})

/** A film from the provider's catalogue search, its poster as the provider gives it. */
export const fromVod = (v: VodItem): LibTitle => ({ id: v.titleId, k: 'm', n: v.name, ...(v.poster && { p: v.poster }) })

/** A film or show the provider lists, its poster on TMDB's host as a path. */
export const providerCard = (id: string, k: 'm' | 't', n: string, poster: string): LibTitle => ({ id, k, n, ...(poster && { p: imagePath(poster) }) })

/** A person TMDB's search found, as a card that opens their page. */
export const personCard = (r: { id: number; name?: string; profile_path?: string | null }): LibTitle => ({ id: `person:${r.id}`, k: 'p', n: r.name ?? '', p: r.profile_path ?? undefined })

const SCORE_KEYS = new Set(['s', 's10', 'sn', 'se', 'ir', 'iv', 'lb', 'lv', 'ta', 'tr', 'tq'])
/** [t] without the scores a build gave it (older builds' sn and se too). */
export const unscored = (t: LibTitle): LibTitle => Object.fromEntries(Object.entries(t).filter(([k]) => !SCORE_KEYS.has(k))) as LibTitle

/** The built library (null when missing), every title it has seen with its tier (the uncut build), scores (with what the APIs said) and kept runtimes. */
export interface Known { lib: Library | null; titles: Map<string, LibTitle>; scores: Map<string, Score>; cache?: ScoreCache; rt?: (id: string) => number | undefined }

/** [Known] from the served library, the uncut build (whose titles it overlays), the score cache, TrueScore's store and the kept runtimes. */
export function knownOf(lib: Library | null, full: Library | null, cache: ScoreCache, films: Store, rt?: (id: string) => number | undefined): Known {
  const titles = new Map<string, LibTitle>()
  for (const t of [...(full?.titles ?? []), ...(lib?.titles ?? [])]) titles.set(t.id, { ...titles.get(t.id), ...t })
  return { lib, titles, scores: scoreTitles(cache, films), cache, rt }
}

/** [t] with the tier, score and runtime the library knows for it. */
export function decorate(k: Known, t: LibTitle): LibTitle {
  const base = k.titles.get(t.id), rt = t.rt ?? base?.rt ?? k.rt?.(t.id)
  return { ...t, ...(base?.t && { t: base.t, ...(base.e && { e: base.e }) }), ...k.scores.get(t.id), ...(rt && { rt }) }
}

/** Best score first, then those with only [s10] by it; unscored titles keep their order after them. */
export const byScore = <T extends LibTitle>(list: T[]) => list.map((t, i) => [t, i] as const).sort(([a, i], [b, j]) => (b.s ?? -Infinity) - (a.s ?? -Infinity) || (b.s10 ?? 0) - (a.s10 ?? 0) || i - j).map(([t]) => t)
