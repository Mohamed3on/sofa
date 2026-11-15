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
const Q = `query($id: ID!, $after: ID) { title(id: $id) { episodes { episodes(first: 250, after: $after) { edges { node { series { episodeNumber { seasonNumber episodeNumber } } aggregateRatingsBreakdown { histogram { histogramValues { rating voteCount } } } } } pageInfo { hasNextPage endCursor } } } } }`

/** An episode's score and net-loved ratio (0–1, two decimals). */
export type EpisodeScore = { ts: number; tr: number }
/** A show's episode scores by "season:episode". */
type Scores = Record<string, EpisodeScore>
const store = jsonFile<Record<string, { at: number; e: Scores }>>(
  () => process.env.EPISODE_SCORES_FILE ?? fileURLToPath(new URL('../.cache/episode-scores.json', import.meta.url)),
  () => ({}),
)
const pending = new Map<string, Promise<Scores | null>>()

type Node = { series?: { episodeNumber?: { seasonNumber?: number; episodeNumber?: number } | null } | null; aggregateRatingsBreakdown?: { histogram?: { histogramValues?: { rating: number; voteCount: number }[] } | null } | null }

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
    out[`${s}:${e}`] = { ts: netScore(h[8] + h[9] - h[0] - h[1], total), tr: Math.round(netShare(h) * 100) / 100 }
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

/** [episodes] with their scores from the show [imdb] ("tt0903747"), when IMDb has them; as they are otherwise. */
export async function withEpisodeScores<E extends { season: number; episode: number }>(episodes: E[], imdb: string | undefined, get: typeof fetch = fetch): Promise<(E & Partial<EpisodeScore>)[]> {
  if (!episodes.length || !imdb || !/^tt\d+$/.test(imdb)) return episodes
  const kept = store.read()[imdb]
  let scores = kept && Date.now() - kept.at < MAX_AGE_MS ? kept.e : null
  if (!scores) {
    let job = pending.get(imdb)
    if (!job) {
      job = fetchScores(imdb, get).then(async (e) => {
        if (e) await store.update((cur) => ({ ...cur, [imdb]: { at: Date.now(), e } })).catch(() => {})
        return e
      }).finally(() => pending.delete(imdb))
      pending.set(imdb, job)
    }
    // IMDb failing serves the last scores kept, however old.
    scores = (await job) ?? kept?.e ?? null
  }
  if (!scores) return episodes
  return episodes.map((e) => ({ ...e, ...scores[`${e.season}:${e.episode}`] }))
}
