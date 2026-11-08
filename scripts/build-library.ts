// Builds the Movies & Shows library (lib/library.ts [rebuildLibrary]) into .cache/library-full.json (every row's
// candidates) and .cache/library.json (what the app is served). Run nightly on the box by deploy/sofa-library.timer.
// Without TMDB_TOKEN, or when every TMDB list fails, nothing is written and the app keeps the last library (or the
// provider's rows).
// First it reads the owner's Trakt and Letterboxd history into .cache/history.json (lib/history.ts), with the
// Watchlist's runtimes (it is shown shortest first). Then the scores (lib/ratings.ts: MDBList and IMDb's
// histograms) are asked for the owner's Watchlist titles first, then whatever the build shows. Each key is optional:
// without one its source is skipped, and rows keep their order.
// Usage: bun scripts/build-library.ts   (XTREAM_*, TMDB_TOKEN, TRAKT_CLIENT_ID, MDBLIST_KEY from env / .env.local)
import { readFile } from 'node:fs/promises'
import { dubbedFilms } from '../lib/arabp2p.js'
import { historyTitles, keepRefreshed, loadHistory, refreshHistory, updateHistory, watchedOf, watchlistRuntimes } from '../lib/history.js'
import { LIBRARY_FILE, LIBRARY_FULL_FILE, providerTitles, rebuildLibrary, saveLibrary, withProviderCopies, type Library } from '../lib/library.js'
import { configFromEnv } from '../lib/provider.js'
import { imdbTopShows, letterboxdFilms, letterboxdLists, loadScores, refreshScores, saveScores } from '../lib/ratings.js'
import { scoreTitles } from '../lib/scores.js'
import { titleCard } from '../lib/titles.js'
import { runtimeOf, tmdbGet } from '../lib/tmdb.js'
import { readOnlyTokens } from '../lib/trakt.js'

// The owner's history first (seconds): Trakt read whole, recommendations included, and the Letterboxd feed.
// Only the server refreshes Trakt's tokens.
readOnlyTokens()
const kept = await loadHistory()
const started = Date.now()
let history = await refreshHistory(kept, { full: true }).then((h) => keepRefreshed(h, started)).catch((e) => (console.log(`history not read: ${e}`), kept))

if (!process.env.TMDB_TOKEN) {
  console.log('TMDB_TOKEN unset: library not built')
  process.exit(0)
}

if (history) {
  const runtimes = await watchlistRuntimes(history)
  // Onto the history as it is now: the server may have written it meanwhile.
  const h = history
  history = await updateHistory((cur) => ({ ...(cur ?? h), runtimes }))
}

const cfg = configFromEnv()
const cache = await loadScores()
let asked = { mdblist: 0, imdb: 0 }
const { full, shown, failed, lookedUp } = await rebuildLibrary({
  tmdb: (path) => tmdbGet(path),
  card: titleCard,
  provider: await providerTitles(cfg),
  copies: (lib) => withProviderCopies(lib, cfg),
  prev: await readFile(LIBRARY_FULL_FILE, 'utf8').catch(() => readFile(LIBRARY_FILE, 'utf8')).then((s) => JSON.parse(s) as Library, () => null),
  seen: watchedOf(history),
  store: () => ({ films: letterboxdFilms(), lists: letterboxdLists() }),
  // A few hundred batched requests at most; the owner's titles first.
  score: async (ids, fresh) => {
    asked = await refreshScores([...new Set([...historyTitles(history), ...ids])], fresh, cache, process.env.MDBLIST_KEY, { save: () => saveScores(cache) })
    await saveScores(cache)
    return scoreTitles(cache, letterboxdFilms())
  },
  topShows: await imdbTopShows(cache),
  // The films ArabP2P has an Arabic dub of that TorBox holds (the server's crawl keeps the list).
  dubbed: await dubbedFilms(),
  // TMDB's kept details, looked up only where none are kept.
  runtime: runtimeOf,
})
await saveLibrary(full, LIBRARY_FULL_FILE)
await saveLibrary(shown)
console.log(`library: ${full.titles.length} titles in ${full.rows.length} rows, ${shown.titles.length} on show; ${lookedUp} provider titles looked up; ${failed} TMDB pages failed; asked MDBList about ${asked.mdblist}, IMDb about ${asked.imdb}`)
