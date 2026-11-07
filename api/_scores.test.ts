import { expect, test } from 'bun:test'
import { ranked, type Library } from './_library'
import { refreshScores, scoreTitles, type ScoreCache } from './_scores'

/** IMDb votes 1..10 with [loved] 10s, [hated] 1s, the rest 7s. */
const votes = (total: number, loved: number, hated = 0) => [hated, 0, 0, 0, 0, 0, total - loved - hated, 0, 0, loved]

test('IMDb histogram scores (loved − hated) × net share; averages shrink toward 6.5 by their priors, weighted by votes', () => {
  const d = [50, 50, 20, 20, 20, 20, 20, 0, 300, 500]
  // Net share 0.7 weighted by the larger crowd (3,000 IMDb votes): 0.49 × 3,000; IMDb 8 on 3,000 → 7.25, Letterboxd 4 (8/10) on 1,000 → 7.25.
  expect(scoreTitles({ a: { h: d, ir: 8, iv: 3000, lb: 4, lv: 1000 } }).get('a')).toEqual({ s: 1470, s10: 7.3, ir: 8, iv: 3000, lb: 4, lv: 1000 })
  expect(scoreTitles({ b: { ir: 9, iv: 3000 } }).get('b')).toEqual({ s10: 7.8, ir: 9, iv: 3000 })
})

test('rows sort by score, then titles with only s10, a title with neither at its row median; rows are cut and unreferenced titles dropped', () => {
  const lib = {
    titles: ['none', 'a', 'b', 'c', 'x', 'avg'].map((id) => ({ id, k: 'm', n: id, sn: 5 })),
    rows: [{ id: 'en', n: 'English', i: [0, 1, 5, 2, 3] }, { id: 'ar', n: 'Arabic', i: [4, 0] }],
  } as Library
  const out = ranked(lib, scoreTitles({ a: { h: votes(100, 100) }, b: { h: votes(100, 50) }, c: { h: votes(100, 10) }, avg: { ir: 9, iv: 1e6 } }), 4)
  const row = (id: string) => out.rows.find((r) => r.id === id)!.i.map((i) => out.titles[i].id)
  expect(row('en')).toEqual(['a', 'none', 'b', 'c'])
  expect(row('ar')).toEqual(['x', 'none']) // no scores: order kept
  expect(out.titles.map((t) => t.id)).toEqual(['none', 'a', 'b', 'c', 'x'])
  expect(out.titles[0]).not.toHaveProperty('sn') // an older build's fields go
  expect(ranked(lib, new Map()).rows[0].i).toEqual([0, 1, 5, 2, 3])
})

test('MDBList is asked in batches and gives the IMDb id IMDb is then asked with; an exhausted quota fills the rest next night', async () => {
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
  let mdbLeft = 1
  const asked: string[] = []
  const fake = (async (url: string, init?: RequestInit) => {
    asked.push(`${url} ${init?.body ?? ''}`)
    if (url.includes('api.mdblist.com')) {
      if (mdbLeft-- <= 0) return json({ error: 'Daily API limit exceeded!' }, 429)
      return json([{ ids: { tmdb: 1, imdb: 'tt1' }, ratings: [{ source: 'imdb', value: 7.8, votes: 5000 }, { source: 'letterboxd', value: 7.8, score: 78, votes: 900 }] }])
    }
    if (url.includes('imdb.com')) return json({ data: { titles: [{ id: 'tt1', ratingsSummary: { aggregateRating: 7.9, voteCount: 5100 }, aggregateRatingsBreakdown: { histogram: { histogramValues: [{ rating: 10, voteCount: 60 }, { rating: 7, voteCount: 40 }] } } }] } })
    throw new Error(url)
  }) as unknown as typeof fetch
  const cache: ScoreCache = {}
  const now = Date.UTC(2026, 10, 5)
  const counts = await refreshScores(['movie:1', 'movie:2', 'tv:3', 'iptv:5'], new Set(), cache, 'm', { fetch: fake, gap: 0, now })
  expect(counts).toEqual({ mdblist: 2, imdb: 1 })
  expect(cache['movie:1']).toEqual({ ma: now, im: 'tt1', ir: 7.9, iv: 5100, lb: 3.9, lv: 900, ha: now, h: [0, 0, 0, 0, 0, 0, 40, 0, 0, 60] })
  expect(cache['movie:2']).toEqual({ ma: now }) // in the batch, MDBList doesn't know it
  expect(cache['tv:3']).toBeUndefined() // MDBList ran out
  asked.length = 0
  mdbLeft = 5
  await refreshScores(['movie:1', 'movie:2', 'tv:3'], new Set(), cache, 'm', { fetch: fake, gap: 0, now: now + 864e5 })
  expect(asked).toEqual(['https://api.mdblist.com/tmdb/show?apikey=m {"ids":["3"]}'])
})
