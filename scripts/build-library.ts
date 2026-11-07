// Builds the Movies & Shows library (api/_library.ts) into .cache/library.json: TMDB's rows, then the
// provider's 4K and Blu-ray films and its shows, each looked up on TMDB once (a title the last build already
// knew keeps its details). Run nightly on the box by deploy/sofa-library.timer. Without TMDB_TOKEN, or when
// every TMDB list fails, nothing is written and the app keeps the last library (or the provider's rows).
// Every row title, provider title and the owner's Watchlist titles are scored first (api/_scores.ts:
// MDBList and IMDb's histograms), then each row keeps its 150 most convincing candidates (TMDB's most popular
// where no score is known), sorted by score and cut to 60. TrueScore's three rows lead: the films its store scores, by its
// recent-adjusted and by its all-time score (each only by that number), looked up on TMDB where new, then IMDb's Top 250 and Most Popular TV shows by TrueScore's net score on IMDb's histogram. Titles on show carry their runtime (TMDB's kept details, looked up where none are kept). Each key is optional: without one its source is skipped, and rows keep their order.
// First it reads the owner's Trakt and Letterboxd history into .cache/history.json (api/_history.ts), with the
// Watchlist's runtimes (it is shown shortest first).
// The "Dubbed in Arabic" row is the films api/_arabp2p.ts found an Arabic dub of that TorBox holds.
// Usage: bun scripts/build-library.ts   (XTREAM_*, TMDB_TOKEN, TRAKT_CLIENT_ID, MDBLIST_KEY from env / .env.local)
import { readFile } from 'node:fs/promises'
import { dubbedFilms } from '../api/_arabp2p.js'
import { configFromEnv } from '../api/_provider.js'
import { assemble, LIBRARY_FILE, LIBRARY_FULL_FILE, onScreen, providerTitles, ranked, rowDefs, saveLibrary, withProviderCopies, type Library } from '../api/_library.js'
import { imdbTopShows, loadScores, refreshScores, saveScores, scoreTitles, trueScoreRows, trueScoreShows } from '../api/_scores.js'
import { fromTmdb, unscored, type LibTitle, type TmdbItem } from '../api/_titles.js'
import { parseTitleId, runtimeOf, tmdbGet, type Kind } from '../api/_tmdb.js'
import { historyTitles, loadHistory, refreshHistory, saveHistory, watchedOf, watchlistRuntimes } from '../api/_history.js'
import { readOnlyTokens } from '../api/_trakt.js'

// The owner's history first (seconds): Trakt read whole, recommendations included, and the Letterboxd feed.
// Only the server refreshes Trakt's tokens.
readOnlyTokens()
const kept = await loadHistory()
let history = await refreshHistory(kept, { full: true }).then(async (h) => (await saveHistory(h), h)).catch((e) => (console.log(`history not read: ${e}`), kept))

if (!process.env.TMDB_TOKEN) {
  console.log('TMDB_TOKEN unset: library not built')
  process.exit(0)
}

if (history) {
  const runtimes = await watchlistRuntimes(history)
  // Onto the history as it is now: the server may have written it meanwhile.
  history = { ...((await loadHistory()) ?? history), runtimes }
  await saveHistory(history)
}

/** [fn] over [items], [n] at a time (TMDB allows about 40 requests a second). */
async function pool<T, R>(items: T[], n: number, fn: (x: T) => Promise<R>): Promise<R[]> {
  const out: R[] = []
  let next = 0
  await Promise.all(Array.from({ length: n }, async () => {
    for (let i = next++; i < items.length; i = next++) out[i] = await fn(items[i])
  }))
  return out
}

const PAGES = 2
let failed = 0
async function list([kind, path]: [Kind, string]): Promise<LibTitle[]> {
  const pages = await Promise.all(Array.from({ length: PAGES }, (_, i) =>
    tmdbGet<{ results: TmdbItem[] }>(`${path}${path.includes('?') ? '&' : '?'}page=${i + 1}`).catch(() => (failed++, { results: [] }))))
  return pages.flatMap((p) => p.results).filter((r) => (r.media_type ?? kind) === kind && r.poster_path && !r.adult).map((r) => fromTmdb(kind, r))
}

const defs = rowDefs()
// A row's queries interleaved (film, show, film, …), so a mixed row isn't all films first.
const seeds = await pool(defs, 3, async (d) => {
  const lists = await Promise.all(d.q.map(list))
  return Array.from({ length: Math.max(0, ...lists.map((l) => l.length)) }, (_, i) => lists.map((l) => l[i])).flat().filter((t): t is LibTitle => !!t)
})
if (!seeds.some((s) => s.length)) throw new Error(`TMDB answered no list (${failed} failed): library kept as it was`)

const gn: Record<string, string> = Object.fromEntries((await Promise.all(['movie', 'tv'].map((k) =>
  tmdbGet<{ genres: { id: number; name: string }[] }>(`genre/${k}/list`).then((r) => r.genres, () => [])))).flat().map((g) => [g.id, g.name]))

const prev = await readFile(LIBRARY_FULL_FILE, 'utf8').catch(() => readFile(LIBRARY_FILE, 'utf8')).then((s) => JSON.parse(s) as Library, () => null)
// TMDB details by title id: this run's rows, then the last build's (without its tiers and scores; looked up again
// when it predates popularity, which picks row candidates).
const known = new Map<string, LibTitle>()
for (const { t: _t, e: _e, ...rest } of [...seeds.flat(), ...(prev?.titles ?? [])]) {
  const meta = unscored(rest)
  if (meta.l && meta.pp !== undefined && !known.has(meta.id)) known.set(meta.id, meta)
}
const cfg = configFromEnv()
const provider = await providerTitles(cfg)
/** Looks up on TMDB the titles in [ids] the build doesn't know yet. */
const lookUp = (ids: string[]) => pool(ids.filter((id) => parseTitleId(id) && !known.has(id)), 6, async (id) => {
  const { kind, tmdb } = parseTitleId(id)!
  const r = await tmdbGet<TmdbItem>(`${kind}/${tmdb}`).catch(() => null)
  if (r && !r.adult) known.set(id, fromTmdb(kind, r))
})
const missing = [...new Set(provider.map((p) => p.t.id))].filter((id) => parseTitleId(id) && !known.has(id))
await lookUp(missing)

// Scores first, the owner's titles and what is on screen now ahead of the rest (a few hundred batched requests at most).
const scores = await loadScores()
const topShows = await imdbTopShows(scores)
const fresh = new Set(seeds[defs.findIndex((d) => d.id === 'new')]?.map((t) => t.id))
const asked = await refreshScores([...new Set([...historyTitles(history), ...(prev ? onScreen(prev) : []), ...seeds.flat().map((t) => t.id), ...provider.map((p) => p.t.id), ...topShows])],
  fresh, scores, process.env.MDBLIST_KEY, { save: () => saveScores(scores) })
await saveScores(scores)
const scored = scoreTitles(scores)
// TrueScore's rows (the server redoes them, and the scores, from library-full.json whenever the store changes).
for (const [row, ids] of trueScoreRows(watchedOf(history))) {
  await lookUp(ids)
  seeds[defs.findIndex((d) => d.id === row)] = ids.flatMap((id) => (known.get(id)?.p ? [known.get(id)!] : []))
}
// The films ArabP2P has an Arabic dub of that TorBox holds (the server's crawl keeps the list).
const dubbed = await dubbedFilms()
await lookUp(dubbed)
seeds[defs.findIndex((d) => d.id === 'ar-dub')] = dubbed.flatMap((id) => (known.get(id)?.p ? [known.get(id)!] : []))
const showIds = trueScoreShows(topShows, scored, watchedOf(history))
await lookUp(showIds)
seeds[defs.findIndex((d) => d.id === 'ts-shows')] = showIds.flatMap((id) => (known.get(id)?.p ? [known.get(id)!] : []))
const full =await withProviderCopies(assemble(defs, seeds, provider.map(({ t, group }) => ({ group, t: known.has(t.id) ? { ...known.get(t.id)!, ...(t.t && { t: t.t }), ...(t.e && { e: t.e }) } : t })), prev, gn, scored), cfg)
await saveLibrary(full, LIBRARY_FULL_FILE)
const shown = ranked(full, scored)
// Each title on show with its runtime: TMDB's kept details, looked up only where none are kept.
const lib = { ...shown, titles: await pool(shown.titles, 6, async (t) => ({ ...t, ...(await runtimeOf(t.id).then((rt) => rt && { rt })) })) }
await saveLibrary(lib)
console.log(`library: ${full.titles.length} titles in ${full.rows.length} rows, ${lib.titles.length} on show; ${missing.length} provider titles looked up; ${failed} TMDB pages failed; asked MDBList about ${asked.mdblist}, IMDb about ${asked.imdb}`)
