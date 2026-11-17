// Each episode's TrueScore, from IMDb's 1–10 histogram of it (one GraphQL query lists a show's episodes 250 at a
// time, each with its histogram): [netScore] and the net-loved ratio, as the IMDb Raycast command scores them. Kept
// a day per show in .cache/episode-scores.json, as new episodes gather votes.
import { fileURLToPath } from 'node:url'
import { jsonFile } from './fs.js'
import { netScore, netShare, sum } from './scores.js'

/** Fewer votes than this and an episode's ratio is noise: it gets no score. */
const MIN_VOTES = 50
const MAX_AGE_MS = 864e5
const HEADERS = { 'content-type': 'application/json', 'x-imdb-client-name': 'imdb-web-next-localized', 'user-agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36' }
const Q = `query($id: ID!, $after: ID) { title(id: $id) { episodes { episodes(first: 250, after: $after) { edges { node { titleText { text } series { episodeNumber { seasonNumber episodeNumber } } aggregateRatingsBreakdown { histogram { histogramValues { rating voteCount } } } } } pageInfo { hasNextPage endCursor } } } } }`

/** An episode's score and net-loved ratio (0–1, two decimals), and its name [n] (kept since 2026-11-17). */
export type EpisodeScore = { ts: number; tr: number; n?: string }
/** A show's episode scores by "season:episode". */
type Scores = Record<string, EpisodeScore>
const store = jsonFile<Record<string, { at: number; e: Scores }>>(
  () => process.env.EPISODE_SCORES_FILE ?? fileURLToPath(new URL('../.cache/episode-scores.json', import.meta.url)),
  () => ({}),
)
const pending = new Map<string, Promise<Scores | null>>()

type Node = { titleText?: { text?: string } | null; series?: { episodeNumber?: { seasonNumber?: number; episodeNumber?: number } | null } | null; aggregateRatingsBreakdown?: { histogram?: { histogramValues?: { rating: number; voteCount: number }[] } | null } | null }

/** The scores of IMDb's episodes ([nodes]) with a number and [MIN_VOTES] votes. */
export function scoreEpisodes(nodes: Node[]): Scores {
  const out: Scores = {}
  for (const n of nodes) {
    const { seasonNumber: s, episodeNumber: e } = n.series?.episodeNumber ?? {}
    if (s == null || e == null) continue
    const h = Array<number>(10).fill(0)
    for (const { rating, voteCount } of n.aggregateRatingsBreakdown?.histogram?.histogramValues ?? []) if (rating >= 1 && rating <= 10) h[rating - 1] = voteCount || 0
    const total = sum(h)
    if (total < MIN_VOTES) continue
    out[`${s}:${e}`] = { ts: netScore(h[8] + h[9] - h[0] - h[1], total), tr: Math.round(netShare(h) * 100) / 100, ...(n.titleText?.text && { n: n.titleText.text }) }
  }
  return out
}

async function fetchScores(imdb: string, get: typeof fetch): Promise<Scores | null> {
  const nodes: Node[] = []
  for (let after: string | undefined, page = 0; page < 8; page++) {
    const r = await get('https://caching.graphql.imdb.com/', { method: 'POST', headers: HEADERS, body: JSON.stringify({ query: Q, variables: { id: imdb, after } }), signal: AbortSignal.timeout(8000) })
      .then((r) => (r.ok ? (r.json() as Promise<{ data?: { title?: { episodes?: { episodes?: { edges: { node: Node }[]; pageInfo: { hasNextPage: boolean; endCursor: string } } } } } }>) : null), () => null)
    const c = r?.data?.title?.episodes?.episodes
    if (!c) return null
    nodes.push(...c.edges.map((x) => x.node))
    if (!c.pageInfo.hasNextPage) break
    after = c.pageInfo.endCursor
  }
  return scoreEpisodes(nodes)
}

/** The show [imdb]'s ("tt0903747") episode scores: kept a day, else asked again (the last kept, however old, when IMDb fails); null when there are none. */
export async function episodeScores(imdb: string | undefined, get: typeof fetch = fetch): Promise<Scores | null> {
  if (!imdb || !/^tt\d+$/.test(imdb)) return null
  const kept = store.read()[imdb]
  if (kept && Date.now() - kept.at < MAX_AGE_MS) return kept.e
  let job = pending.get(imdb)
  if (!job) {
    job = fetchScores(imdb, get).then(async (e) => {
      if (e) await store.update((cur) => ({ ...cur, [imdb]: { at: Date.now(), e } })).catch(() => {})
      return e
    }).finally(() => pending.delete(imdb))
    pending.set(imdb, job)
  }
  return (await job) ?? kept?.e ?? null
}

/** [episodes] with their scores from the show [imdb] ("tt0903747"), when IMDb has them; as they are otherwise. */
export async function withEpisodeScores<E extends { season: number; episode: number }>(episodes: E[], imdb: string | undefined, get: typeof fetch = fetch): Promise<(E & Partial<EpisodeScore>)[]> {
  const scores = episodes.length ? await episodeScores(imdb, get) : null
  if (!scores) return episodes
  return episodes.map((e) => {
    const { n: _n, ...s } = scores[`${e.season}:${e.episode}`] ?? {}
    return { ...e, ...s }
  })
}

/** Episodes one show gives the TrueScore episodes row at most, so a few shows don't fill it. */
const PER_SHOW = 3

/** A card of the TrueScore episodes row: its episode id ("tv:1396:s5e14"), its show's, its score, ratio and name, and its still's TMDB path ('' without one). */
export type BestEpisode = EpisodeScore & { id: string; show: string; still: string }

/**
 * The TrueScore episodes row: the kept episodes of [shows] (title ids; [imdb] gives each one's IMDb id) by TrueScore, best
 * first, [PER_SHOW] a show, until [n] the owner hasn't watched ([seen]) are in: like the other TrueScore rows, the watched ones
 * stay among them for the app to show or hide (Hide watched titles). Only episodes [listed] (a show's episode
 * ids as its page numbers them) has too: IMDb numbers some shows apart (One Piece's episodes all in season 1), and
 * a card must open its show's page on itself. [listed] (by episode id, each its still) is asked only of shows still in the running.
 */
export async function bestEpisodes(shows: string[], imdb: (show: string) => string | undefined, seen: Set<string>, listed: (show: string) => Promise<Map<string, string>>, n = 90): Promise<BestEpisode[]> {
  const kept = store.read()
  const all = shows.flatMap((show) => Object.entries(kept[imdb(show) ?? '']?.e ?? {}).map(([k, s]) => ({ ...s, show, id: `${show}:s${k.replace(':', 'e')}` })))
    // Specials (season 0) aren't the show's own.
    .filter((x) => !x.id.includes(':s0e'))
    .sort((a, b) => b.ts - a.ts)
  const ids = new Map<string, Promise<Map<string, string>>>(), taken = new Map<string, number>(), out: BestEpisode[] = []
  let unseen = 0
  for (const x of all) {
    if (unseen >= n) break
    if ((taken.get(x.show) ?? 0) >= PER_SHOW) continue
    if (!ids.has(x.show)) ids.set(x.show, listed(x.show).catch(() => new Map<string, string>()))
    const still = (await ids.get(x.show)!).get(x.id)
    if (still === undefined) continue
    taken.set(x.show, (taken.get(x.show) ?? 0) + 1)
    if (!seen.has(x.id)) unseen++
    out.push({ ...x, still })
  }
  return out
}
