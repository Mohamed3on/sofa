// The Movies & Shows library (KTD17): title-first rows built nightly by scripts/build-library.ts from TMDB
// (trending, new releases, genres, then original language: English, Arabic, Russian, everything else), with
// the provider's 4K and Blu-ray films and its shows merged in, each title with its best known picture tier.
// Kept in .cache/library.json; /api/app/library serves it from memory, or the provider's own rows when it
// is missing or older than 3 days (TMDB down or unset). Its shape is [Library]: titles once each, rows as
// indexes into them, short keys, image paths without TMDB's host and size.
import { readFile, stat } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { writeAtomic } from './_fs.js'
import { vodChannels } from './_lib.js'
import { claims4K, type XtreamConfig } from './_provider.js'
import { measuredStream, pictureTier, probedPicture, type Tier } from './_quality-labels.js'
import { loadScores, scoreTitles, trueScoreRows, type Score } from './_scores.js'
import { measuredFilms, showList } from './_shows.js'
import { keptRuntimes, parseTitleId, TMDB_IMG, tmdbGet, type Kind } from './_tmdb.js'
import { fromTmdb, knownOf, providerCard, unscored, type Known, type LibTitle, type TmdbItem } from './_titles.js'
import { bestFilmCopies, blurayMovies, filmTitleOf } from './_vod.js'

export interface Row { id: string; n: string; /** Indexes into Library.titles. */ i: number[] }
export interface Library { v: 1; src: 'tmdb' | 'provider'; at: string; img: string; gn: Record<string, string>; titles: LibTitle[]; rows: Row[] }

/** A row: TMDB queries whose results lead it, then every library title it [has]. */
export interface RowDef { id: string; n: string; q: [Kind, string][]; has?: (t: LibTitle) => boolean }

const NO_TALK = 'without_genres=10763,10764,10767' // news, reality, talk
/** Language rows: [code, name, minimum film votes on TMDB (shows a third)]. */
const LANGS: [string, string, number][] = [['en', 'English', 300], ['ar', 'Arabic', 10], ['ru', 'Russian', 50]]
/** Genre rows: [id, name, TMDB film genre, TMDB show genre]. */
const GENRES: [string, string, number?, number?][] = [
  ['action', 'Action', 28, 10759], ['comedy', 'Comedy', 35, 35], ['drama', 'Drama', 18, 18], ['thriller', 'Thriller', 53],
  ['crime', 'Crime', 80, 80], ['scifi', 'Science fiction', 878, 10765], ['horror', 'Horror', 27], ['romance', 'Romance', 10749],
  ['animation', 'Animation', 16, 16], ['documentary', 'Documentary', 99, 99],
]

export function rowDefs(today = new Date()): RowDef[] {
  const day = (ago: number) => new Date(today.getTime() - ago * 864e5).toISOString().slice(0, 10)
  const pop = 'sort_by=popularity.desc'
  const films = (q: string): [Kind, string] => ['movie', `discover/movie?${pop}&${q}`]
  const shows = (q: string): [Kind, string] => ['tv', `discover/tv?${pop}&${q}&${NO_TALK}`]
  return [
    // TrueScore's films by its recent-adjusted, then its all-time score: filled and ordered by scripts/build-library.ts.
    { id: 'ts-recent', n: 'TrueScore · recent', q: [] },
    { id: 'ts-all', n: 'TrueScore · all-time', q: [] },
    // IMDb's Top 250 and Most Popular TV shows by TrueScore's net score on IMDb's histogram: filled and ordered by scripts/build-library.ts.
    { id: 'ts-shows', n: 'TrueScore · shows', q: [] },
    { id: 'trending', n: 'Trending', q: [['movie', 'trending/movie/week'], ['tv', 'trending/tv/week']] },
    // Animated films with an Arabic dub TorBox holds (api/_arabp2p.ts): filled by scripts/build-library.ts.
    { id: 'ar-dub', n: 'Dubbed in Arabic', q: [] },
    // Films out on digital or disc in the last 60 days; shows with an episode out in them.
    { id: 'new', n: 'New releases', q: [films(`with_release_type=4%7C5&release_date.gte=${day(60)}&release_date.lte=${day(0)}&vote_count.gte=20`), shows(`air_date.gte=${day(60)}&air_date.lte=${day(0)}&vote_count.gte=100`)] },
    ...GENRES.map(([id, n, film, show]): RowDef => ({
      id, n,
      q: [...(film ? [films(`with_genres=${film}&vote_count.gte=300`)] : []), ...(show ? [shows(`with_genres=${show}&vote_count.gte=100`)] : [])],
      has: (t) => !!t.g?.includes((t.k === 'm' ? film : show) ?? -1),
    })),
    ...LANGS.map(([l, n, votes]): RowDef => ({
      id: l, n,
      q: [films(`with_original_language=${l}&vote_count.gte=${votes}`), shows(`with_original_language=${l}&vote_count.gte=${Math.ceil(votes / 3)}`)],
      has: (t) => t.l === l,
    })),
    { id: 'other', n: 'Other languages', q: [], has: (t) => !LANGS.some(([l]) => l === t.l) },
  ]
}

const RANK: Record<Tier, number> = { Reference: 0, Great: 1, OK: 2, Soft: 3 }
/** [base] with [other]'s tier when that one is better known: measured beats claimed, then the higher tier. */
function withTier(base: LibTitle, other: LibTitle): LibTitle {
  const better = !!other.t && (!base.t || (!other.e && !!base.e) || (!other.e === !base.e && RANK[other.t] < RANK[base.t]))
  if (!better) return base
  const { t: _t, e: _e, ...rest } = base
  return { ...rest, t: other.t, ...(other.e && { e: 1 as const }) }
}

function toLibrary(src: Library['src'], titles: Map<string, LibTitle>, rows: { id: string; n: string; ids: string[] }[], gn: Record<string, string>): Library {
  const list = [...titles.values()]
  const index = new Map(list.map((t, i) => [t.id, i]))
  return {
    v: 1, src, at: new Date().toISOString(), img: TMDB_IMG, gn, titles: list,
    rows: rows.map(({ id, n, ids }) => ({ id, n, i: [...new Set(ids.map((x) => index.get(x)!))] })).filter((r) => r.i.length),
  }
}

/** A row that comes in its own order (TrueScore's, by one exact score), kept as built, whole, rather than sorted by [s] and cut (the app leaves out watched titles, then shows 60). */
export const ordered = (rowId: string) => rowId.startsWith('ts-')

/** Candidates a row keeps, the most convincing first (api/_scores.ts [s]; TMDB popularity after, for titles without), before scoring cuts it to ROW_CAP. */
export const ROW_CANDIDATES = 150

/** One copy the provider has: a 4K or Blu-ray film, or a show, with the row it sits in today. */
export interface ProviderTitle { t: LibTitle; group: string }

/**
 * The library from TMDB's rows ([seeds], one list per def) and the provider's titles (already given their TMDB
 * details where known), keeping tiers seen in the [prev] build; [scores] pick each row's candidates.
 */
export function assemble(defs: RowDef[], seeds: LibTitle[][], provider: ProviderTitle[], prev: Library | null, gn: Record<string, string>, scores = new Map<string, { s?: number }>()): Library {
  const titles = new Map<string, LibTitle>()
  for (const t of seeds.flat()) if (!titles.has(t.id)) titles.set(t.id, t)
  for (const { t } of provider) titles.set(t.id, titles.has(t.id) ? withTier(titles.get(t.id)!, t) : t)
  for (const p of prev?.titles ?? []) if (titles.has(p.id)) titles.set(p.id, withTier(titles.get(p.id)!, p))
  const all = [...titles.values()]
  const s = (id: string) => scores.get(id)?.s, key = (id: string) => s(id) ?? titles.get(id)!.pp ?? -1
  const candidates = (ids: string[]) => [...new Set(ids)].sort((a, b) => Number(s(b) !== undefined) - Number(s(a) !== undefined) || key(b) - key(a)).slice(0, ROW_CANDIDATES)
  return toLibrary('tmdb', titles, defs.map((d, i) => ({ id: d.id, n: d.n, ids: ordered(d.id) ? seeds[i].map((t) => t.id) : candidates([...seeds[i].map((t) => t.id), ...(d.has ? all.filter(d.has).map((t) => t.id) : [])]) })), gn)
}

/** The provider's 4K and Blu-ray films (tier measured on play, else the category's claim) and its shows. */
export async function providerTitles(cfg: XtreamConfig): Promise<ProviderTitle[]> {
  const [vod, titleOf, bluray, shows, measured] = await Promise.all([
    vodChannels(cfg).catch(() => []), filmTitleOf(cfg), blurayMovies(cfg).catch(() => []), showList(cfg).catch(() => null), measuredFilms(),
  ])
  const film = (vodId: number, id: string, n: string, poster: string, group: string, claim: Tier): ProviderTitle => {
    const m = measured[String(vodId)]
    const t = m && pictureTier(probedPicture(measuredStream(m, m.kbps))!)
    return { t: { ...providerCard(id, 'm', n, poster), ...(t ? { t } : { t: claim, e: 1 as const }) }, group }
  }
  return [
    ...vod.filter((c) => claims4K(c.group)).map((c) => film(c.streamId, titleOf(c.streamId), c.name, c.logo, c.group, 'Reference')),
    ...bluray.map((m) => film(m.id, m.titleId, m.name, m.poster, m.group, 'Great')),
    ...(shows?.movies ?? []).map((s) => ({ t: providerCard(s.titleId, 't', s.name, s.poster), group: s.group })),
  ]
}

/** [lib] with each film's best provider copy ([LibTitle.v], [LibTitle.x]). */
export async function withProviderCopies(lib: Library, cfg: XtreamConfig): Promise<Library> {
  const best = await bestFilmCopies(cfg)
  return { ...lib, titles: lib.titles.map((t) => (best.has(t.id) ? { ...t, v: best.get(t.id)![0], x: best.get(t.id)![1] } : t)) }
}

/** The provider's rows as a library, for when TMDB's is missing or stale. */
export async function providerLibrary(cfg: XtreamConfig): Promise<Library> {
  const titles = new Map<string, LibTitle>()
  const rows = new Map<string, string[]>()
  for (const { t, group } of await providerTitles(cfg)) {
    titles.set(t.id, titles.has(t.id) ? withTier(titles.get(t.id)!, t) : t)
    rows.get(group)?.push(t.id) ?? rows.set(group, [t.id])
  }
  return withProviderCopies(toLibrary('provider', titles, [...rows].map(([n, ids], i) => ({ id: `p${i}`, n, ids })), {}), cfg)
}

/** Titles a row keeps after sorting (the rest stay findable through search). */
export const ROW_CAP = 60

const median = (xs: number[]) => xs.slice().sort((a, b) => a - b)[Math.floor(xs.length / 2)]

/**
 * [lib] with [scores] on its titles and each row sorted by score (one with only [s10] after those, by it; one with
 * neither at the row's median),
 * cut to [cap] titles, then only the titles some row still holds.
 */
export function ranked(lib: Library, scores: Map<string, Score>, cap = ROW_CAP): Library {
  const all = lib.titles.map(({ pp: _p, ...t }) => ({ ...unscored(t), ...scores.get(t.id) }))
  const rows = lib.rows.map((r) => {
    const known = r.i.map((i) => all[i].s).filter((s): s is number => s !== undefined)
    const mid = known.length ? median(known) : 0, lo = Math.min(0, ...known)
    const key = (i: number) => all[i].s ?? (all[i].s10 !== undefined ? lo - 10 + all[i].s10! : mid)
    return { ...r, i: (!ordered(r.id) && r.i.some((i) => all[i].s10 !== undefined || all[i].s !== undefined) ? r.i.slice().sort((a, b) => key(b) - key(a)) : r.i).slice(0, ordered(r.id) ? undefined : cap) }
  })
  const kept = [...new Set(rows.flatMap((r) => r.i))].sort((a, b) => a - b)
  const index = new Map(kept.map((old, i) => [old, i]))
  return { ...lib, titles: kept.map((i) => all[i]), rows: rows.map((r) => ({ ...r, i: r.i.map((i) => index.get(i)!) })) }
}

/** Title ids as the app shows them: every row's first card, then every row's second, and so on. */
export function onScreen(lib: Library): string[] {
  const ids = new Set<string>()
  for (let p = 0; p < Math.max(0, ...lib.rows.map((r) => r.i.length)); p++) for (const r of lib.rows) if (p < r.i.length) ids.add(lib.titles[r.i[p]].id)
  return [...ids]
}

const lookedUp = new Map<string, LibTitle | null>()
/**
 * The nightly build's library (library-full.json) with TrueScore's rows (the owner's [seen] titles counted out) and
 * every score as its store and the score cache have them now, ranked and cut as the build does: what the server
 * serves between builds once the store changes. A film new to the rows is looked up on TMDB once; titles carry their
 * kept runtimes.
 */
export async function rescoredLibrary(seen: Set<string>): Promise<Library> {
  const [full, cache, rt] = await Promise.all([readFile(LIBRARY_FULL_FILE, 'utf8').then((s) => JSON.parse(s) as Library), loadScores(), keptRuntimes()])
  const titles = [...full.titles], index = new Map(titles.map((t, i) => [t.id, i]))
  const ts = trueScoreRows(seen)
  await Promise.all(ts.flatMap(([, ids]) => ids).filter((id) => !index.has(id) && !lookedUp.has(id)).map((id) =>
    tmdbGet<TmdbItem>(`movie/${parseTitleId(id)!.tmdb}`).then((r) => lookedUp.set(id, r.adult ? null : fromTmdb('movie', r)), () => {})))
  const names = new Map(rowDefs().map((d) => [d.id, d.n]))
  const rows = [
    ...ts.map(([id, ids]) => ({ id, n: names.get(id)!, i: [...new Set(ids.flatMap((x) => {
      const t = lookedUp.get(x)
      if (!index.has(x) && t?.p) index.set(x, titles.push(t) - 1)
      return titles[index.get(x) ?? -1]?.p ? [index.get(x)!] : []
    }))] })).filter((r) => r.i.length),
    ...full.rows.filter((r) => !ts.some(([id]) => id === r.id)),
  ]
  const shown = ranked({ ...full, titles, rows }, scoreTitles(cache))
  return { ...shown, titles: shown.titles.map((t) => ({ ...t, ...(rt(t.id) && { rt: rt(t.id) }) })) }
}

const KNOWN_MS = 10 * 60e3
let known: { at: number; job: Promise<Known> } | null = null

/** [Known] (api/_titles.ts) from the library files, the scores and the kept runtimes, read again at most every 10 minutes (the nightly build rewrites them). */
export function knownTitles(): Promise<Known> {
  if (known && Date.now() - known.at < KNOWN_MS) return known.job
  const read = (f: string) => readFile(f, 'utf8').then((s) => JSON.parse(s) as Library, () => null)
  const job = Promise.all([read(LIBRARY_FILE), read(LIBRARY_FULL_FILE), loadScores(), keptRuntimes()]).then(([lib, full, cache, rt]) => knownOf(lib, full, cache, rt))
  known = { at: Date.now(), job }
  return job
}

export const LIBRARY_FILE = fileURLToPath(new URL('../.cache/library.json', import.meta.url))
/** The build's unscored library (every title; rows hold their ROW_CANDIDATES, not yet cut to ROW_CAP): the next build's memory of TMDB details and tiers. */
export const LIBRARY_FULL_FILE = fileURLToPath(new URL('../.cache/library-full.json', import.meta.url))

/** Writes the library atomically (temp + rename). */
export const saveLibrary = (lib: Library, file = LIBRARY_FILE) => writeAtomic(file, lib)

const STALE_MS = 3 * 864e5
const PROVIDER_MS = 30 * 60e3
export interface Served { body: Uint8Array; etag: string }
const served = (body: Uint8Array): Served => ({ body, etag: `"${Bun.hash(body).toString(36)}"` })
let fromFile: { key: string; job: Promise<Served> } | null = null
let fallback: { at: number; job: Promise<Served> } | null = null

/**
 * The library's bytes: library.json, or [rescored]'s once TrueScore's store has changed since it was built ([storeAt],
 * when the store was written), each made again only when one of them changes; else [provider]'s, rebuilt at most every
 * 30 minutes.
 */
export async function servedLibrary(provider: () => Promise<Library>, rescored: () => Promise<Library>, storeAt: () => number, file = LIBRARY_FILE): Promise<Served> {
  const mtime = (await stat(file).catch(() => null))?.mtimeMs ?? 0
  if (Date.now() - mtime < STALE_MS) {
    const at = storeAt(), key = at > mtime ? `${mtime}:${at}` : `${mtime}`
    if (fromFile?.key !== key) {
      const job = (at > mtime ? rescored().then((lib) => served(Buffer.from(JSON.stringify(lib)))) : Promise.reject())
        .catch((e) => (e && console.log(`library not rescored: ${e}`), readFile(file).then(served)))
      fromFile = { key, job }
      job.catch(() => fromFile?.job === job && (fromFile = null))
    }
    return fromFile.job
  }
  fromFile = null
  if (!fallback || Date.now() - fallback.at > PROVIDER_MS) {
    const job = provider().then((lib) => served(Buffer.from(JSON.stringify(lib))))
    fallback = { at: Date.now(), job }
    job.catch(() => fallback?.job === job && (fallback = null))
  }
  return fallback.job
}
