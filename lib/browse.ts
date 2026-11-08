// What the app's title pages, search and person pages ask beyond the library (U13): a title's TMDB details
// with More like this, cast and director (and a TMDB-only show's episodes, so Real-Debrid can play them),
// search over titles and category words, and a person's titles. Every title comes in the library's shape
// (titles.ts LibTitle) with the tier and score the library knows for it.
import type { XtreamConfig } from './provider.js'
import { knownTitles } from './library.js'
import { letterboxdFilms, scoreNow } from './ratings.js'
import { due, histogramShare, scoreOf, scoreTitles } from './scores.js'
import { showList } from './shows.js'
import { episodeTitle, parseTitleId, tmdbCollection, tmdbEpisodes, tmdbImage, tmdbLogo, tmdbTitle } from './tmdb.js'
import { byScore, decorate, fromRecommendation, fromTmdb, fromVod, personCard, type Known, type LibTitle, type TmdbItem } from './titles.js'
import type { VodItem } from './vod.js'

/**
 * A film's or show's page beyond its card, in the app's MovieInfo shape: plot, runtime, genres, backdrop,
 * cast and director (and as people, for their pages), More like this, its score; a show the provider lists
 * gives its series id (episodes come from the provider), one it lacks its aired episodes from TMDB.
 * A title whose MDBList or IMDb answer is due asks now ([scoreNow]): its scores join this answer if they come
 * back before the rest of it, else the next one (the page never waits for them).
 * null for an id TMDB doesn't know (or without TMDB_TOKEN).
 */
export async function titleDetails(cfg: XtreamConfig, id: string) {
  const p = parseTitleId(id)
  if (!p || p.season != null) return null
  const { kind, tmdb } = p
  const [t, k, logo] = await Promise.all([tmdbTitle(kind, tmdb), knownTitles(), tmdbLogo(kind, tmdb)])
  if (!t) return null
  const cache = k.cache
  if (cache && Object.values(due(cache[id])).some(Boolean)) {
    void scoreNow(id, t.imdb || undefined, process.env.MDBLIST_KEY, cache[id]).then((e) => {
      if (!e) return
      cache[id] = { ...cache[id], ...e }
      const s = scoreTitles({ [id]: cache[id] }, letterboxdFilms()).get(id)
      if (s) k.scores.set(id, s)
    })
  }
  const parts = t.collection ? (await tmdbCollection(t.collection.id)).filter((x) => String(x.id) !== tmdb) : []
  const series = kind === 'tv' ? (await showList(cfg).catch(() => null))?.movies.find((s) => s.titleId === id)?.id : undefined
  const episodes = kind === 'tv' && !series ? await tmdbEpisodes(tmdb, t.seasons) : []
  const people = new Map<number, { id: number; name: string; role: string }>()
  for (const d of t.directors) people.set(d.id, { id: d.id, name: d.name, role: kind === 'tv' ? 'Creator' : 'Director' })
  for (const c of t.cast) if (!people.has(c.id)) people.set(c.id, { id: c.id, name: c.name, role: c.character })
  return {
    titleId: id,
    plot: t.plot,
    secs: kind === 'movie' ? t.runtime * 60 : 0,
    genre: t.genres.slice(0, 2).join(', '),
    backdrop: tmdbImage(t.backdrop, 'w1280'),
    cast: t.cast.slice(0, 4).map((c) => c.name).join(', '),
    director: t.directors.map((d) => d.name).join(', '),
    people: [...people.values()],
    // The franchise's own films are its row, not More like this.
    more: t.recommendations.filter((r) => !parts.some((x) => r.title === `movie:${x.id}`)).map((r) => decorate(k, fromRecommendation(r))),
    ...scoreOf(id, cache ?? {}, letterboxdFilms()),
    ...histogramShare(id, cache ?? {}, letterboxdFilms()),
    ...(t.trailer && { trailer: t.trailer }),
    ...(logo && { logo }),
    ...(languageOf(t.originalLanguage) && { language: languageOf(t.originalLanguage) }),
    // Only when it says more than the name does ("Amélie" → "Le fabuleux destin d'Amélie Poulain", never "Léon" → "Leon").
    ...(t.originalTitle && letters(t.originalTitle) !== letters(t.name) && { originalTitle: t.originalTitle }),
    ...(parts.length && { collection: t.collection!.name, parts: parts.map((x) => decorate(k, fromTmdb('movie', x))) }),
    ...(t.network && { network: t.network }),
    ...(t.status && { status: t.status }),
    ...(t.next && { next: t.next }),
    ...(series && { series }),
    ...(episodes.length && {
      episodes: episodes.map((e) => ({ id: -(e.season * 10_000 + e.episode), titleId: episodeTitle(id, e.season, e.episode), ...e })),
    }),
  }
}

interface Credit extends TmdbItem { vote_count?: number; job?: string; profile_path?: string | null }
const NOT_THEIR_WORK = new Set([10763, 10764, 10767]) // news, reality, talk: appearances

/** A person's films and shows (acted in, directed or created), best score first, then the most voted; at most 60. */
export function personTitles(credits: { cast?: Credit[]; crew?: Credit[] }, k: Known): LibTitle[] {
  const seen = new Map<string, LibTitle & { v: number }>()
  for (const c of [...(credits.cast ?? []), ...(credits.crew ?? []).filter((c) => c.job === 'Director' || c.job === 'Creator')]) {
    if ((c.media_type !== 'movie' && c.media_type !== 'tv') || c.adult || !c.poster_path || c.genre_ids?.some((g) => NOT_THEIR_WORK.has(g))) continue
    const t = fromTmdb(c.media_type, c)
    if (!seen.has(t.id)) seen.set(t.id, { ...decorate(k, t), v: c.vote_count ?? 0 })
  }
  return byScore([...seen.values()].sort((a, b) => b.v - a.v)).slice(0, 60).map(({ v: _v, ...t }) => t)
}

/** Search words for rows whose id or name isn't the word itself. */
const ROW_WORDS: Record<string, string> = { 'sci-fi': 'scifi', 'sci fi': 'scifi', 'science fiction': 'scifi', comedies: 'comedy', documentaries: 'documentary', new: 'new', latest: 'new', popular: 'trending' }
/** Languages a search can name ("korean"), beyond the library's own language rows. */
const LANGUAGES = ['fr', 'de', 'es', 'it', 'pt', 'ja', 'ko', 'zh', 'hi', 'tr', 'sv', 'da', 'no', 'fi', 'nl', 'pl', 'fa', 'he', 'th', 'id', 'el', 'cs', 'hu', 'ro', 'uk', 'ta', 'te', 'ml', 'cn']
const languageName = new Intl.DisplayNames(['en'], { type: 'language' })

export interface SearchDeps {
  known: Known
  /** TMDB GET (absent without TMDB_TOKEN). */
  tmdb?: <T>(path: string) => Promise<T>
  /** The provider's catalogue search. */
  vod?: (q: string) => Promise<VodItem[]>
  /** The owner's Watchlist (lifted) and watched titles (lowered a little). */
  history?: { watchlist: Set<string>; watched: Set<string> }
}

/** An ISO 639-1 code's English name ("fr" → "French"); "" for TMDB's "xx" (no language) and codes it doesn't know. */
function languageOf(code: string) {
  if (code === 'cn') return 'Cantonese' // TMDB's own code
  const name = code ? languageName.of(code) : ''
  return name && name !== code ? name : ''
}
const letters = (s: string) => norm(s).replace(/[^\p{L}\p{N}]+/gu, '')

const norm = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/\s+/g, ' ').trim()

/**
 * Titles for a search (R20). A category word gives its row ("arabic", "comedy"), or TMDB's discover for a
 * genre or language the rows don't have ("korean", "fantasy"), best score first. Anything else is TMDB's
 * title search (films and shows, its own order), then the provider's catalogue and the library's titles it
 * didn't find; at most 60.
 */
export async function searchTitles(query: string, d: SearchDeps): Promise<LibTitle[]> {
  const q = norm(query)
  if (!q) return []
  const lib = d.known.lib
  const row = lib?.rows.find((r) => r.id === (ROW_WORDS[q] ?? q) || norm(r.n) === q)
  if (row) return row.i.map((i) => lib!.titles[i])
  const genres = Object.entries(lib?.gn ?? {}).filter(([, n]) => norm(n) === q).map(([id]) => id)
  const lang = LANGUAGES.find((c) => norm(languageName.of(c) ?? '') === q)
  if (d.tmdb && (genres.length || lang)) {
    const by = lang ? `with_original_language=${lang}` : `with_genres=${genres.join('%7C')}`
    const lists = await Promise.all((['movie', 'tv'] as const).map((kind) =>
      d.tmdb!<{ results?: TmdbItem[] }>(`discover/${kind}?sort_by=popularity.desc&vote_count.gte=${kind === 'movie' ? 100 : 30}&${by}`)
        .then((r) => (r.results ?? []).map((x) => fromTmdb(kind, x)), () => [])))
    return byScore(lists.flat().map((t) => decorate(d.known, t)))
  }
  const [found, vod] = await Promise.all([
    d.tmdb?.<{ results?: Credit[] }>(`search/multi?query=${encodeURIComponent(query.trim())}&include_adult=false`).then((r) => r.results ?? [], () => []) ?? [],
    d.vod?.(query).catch(() => []) ?? [],
  ])
  const out = new Map<string, LibTitle>()
  const words = q.split(' ')
  // Each word starts a word of the name ("pitt" finds The Pitt, not "Pittsburgh Panthers" by its inner "the").
  const named = (n: string) => { const ws = norm(n).split(' '); return words.every((w) => ws.some((x) => x.startsWith(w))) }
  // People whose name matches ("nolan", "zendaya"), as cards that open their page: after any title named like the
  // search (searches are mostly for a film or show), first when none is.
  const people = found
    .filter((r) => r.media_type === 'person' && r.profile_path && named(r.name ?? ''))
    .slice(0, 3)
    .map(personCard)
  for (const r of found) if ((r.media_type === 'movie' || r.media_type === 'tv') && r.poster_path) out.set(fromTmdb(r.media_type, r).id, fromTmdb(r.media_type, r))
  const provider = new Set<string>()
  for (const v of vod) {
    provider.add(v.titleId)
    if (!out.has(v.titleId)) out.set(v.titleId, fromVod(v))
  }
  for (const t of d.known.titles.values()) if (out.size < 60 && !out.has(t.id) && named(t.n)) out.set(t.id, t)
  // Titles: named like the search (whole name or its start), then by its words, then the rest; within each, the
  // Watchlist first, then conviction ([LibTitle.s], else TMDB popularity; in doublings, so Dune: Part Two beats an obscure "Dune"), then a copy
  // known here, then TMDB's order; a title already watched drops below its equals.
  const match = (n: string) => (norm(n).startsWith(q) ? 0 : named(n) ? 1 : 2)
  const ranked = [...out.values()].slice(0, 60).map((t) => decorate(d.known, t)).map((t, i) => ({ t, i, key: [
    match(t.n), d.history?.watchlist.has(t.id) ? 0 : 1, -Math.sign(t.s ?? 1) * Math.floor(Math.log2(Math.abs(t.s ?? t.pp ?? 0) + 1)),
    t.t || provider.has(t.id) ? 0 : 1, d.history?.watched.has(t.id) ? 1 : 0,
  ] }))
  const first = (a: number[], b: number[]) => a.map((v, j) => v - b[j]).find((d) => d !== 0) ?? 0
  ranked.sort((a, b) => first(a.key, b.key) || a.i - b.i)
  const strong = ranked.filter((r) => r.key[0] === 0).length
  const titles = ranked.map((r) => r.t)
  return [...titles.slice(0, strong), ...people, ...titles.slice(strong)]
}
