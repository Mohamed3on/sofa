// Scores for the library (KTD16), pure math over what the ratings sources said (_ratings.ts). Rows sort by [s],
// TrueScore's (recent-adjusted, else all-time) score where its store has one, else its conviction on Letterboxd's
// histogram from there, else on IMDb's 1–10 one (loved 9–10 minus hated 1–2, weighted by that net share); titles
// without one follow, by [s10], and a title with neither sorts at its row's median. [s10] is IMDb's and Letterboxd's
// averages (MDBList), each pulled toward 6.5/10 by a Bayesian prior (3,000 votes for IMDb, 1,000 for Letterboxd),
// averaged by votes.

/**
 * What was said about one title, times in ms: IMDb average (/10) and votes, Letterboxd average (0–5) and votes,
 * when MDBList was asked; the IMDb id, IMDb's votes for 1 to 10, and when IMDb was asked.
 */
export interface ScoreEntry { ir?: number; iv?: number; lb?: number; lv?: number; ma?: number; im?: string; h?: number[]; ha?: number }
export type ScoreCache = Record<string, ScoreEntry>
/** A film in TrueScore's store: Letterboxd's histogram (½★ to 5★), its all-time and recent-adjusted scores and its net-loved ratio. */
export type StoreFilm = { h?: number[]; score?: number; adjusted?: number; ratio?: number }
/** TrueScore's store by movie:<tmdb> (_ratings.ts [letterboxdFilms]). */
export type Store = Map<string, StoreFilm>
/** A title's score as a card carries it; absent fields are unknown. */
export interface Score {
  /** Combined score (see the top) on /10, one decimal, and the sort key [s]. */
  s10?: number
  s?: number
  /** IMDb average (/10) and votes, Letterboxd average (0–5) and votes, from MDBList. */
  ir?: number
  iv?: number
  lb?: number
  lv?: number
  /** TrueScore's adjusted score, all-time and recent net-loved ratios (0–1), for a film in its store. */
  ta?: number
  tr?: number
  tq?: number
}
const DAY = 864e5

export const sum = (d: number[]) => d.reduce((a, b) => a + b, 0)

/** TrueScore's net score: net × (net / total), sign kept. */
export const netScore = (net: number, total: number) => (total > 0 ? Math.sign(net) * Math.round((net * net) / total) : 0)

/** A histogram's net-loved share: (loved − hated) / total, loved its top two buckets, hated its bottom two; 0 when empty. */
export const netShare = (h: number[]) => (sum(h) ? (h[8] + h[9] - h[0] - h[1]) / sum(h) : 0)

/** Whether [e]'s MDBList (monthly) or IMDb (weekly) answer is due; both daily for a [fresh] title (a new release). */
export const due = (e: ScoreEntry | undefined, now = Date.now(), fresh = false) => ({ mdb: !(e?.ma! > now - (fresh ? 1 : 30) * DAY), imdb: !(e?.ha! > now - (fresh ? 1 : 7) * DAY) })

/** One title's score as it is now. */
export function scoreOf(id: string, cache: ScoreCache, films: Store) {
  const f = films.get(id)
  return scoreTitles({ [id]: cache[id] ?? {} }, new Map(f ? [[id, f]] : [])).get(id)
}

/** [ids] (best first) up to and including the 90th the owner hasn't watched ([seen]). */
function untilUnseen(ids: string[], seen: Set<string>) {
  let unseen = 0
  return ids.filter((id) => unseen < 90 && (seen.has(id) || ++unseen, true))
}

/**
 * TrueScore's rows by id: the films its store has that exact score for (recent-adjusted, all-time), best first, until
 * 90 the owner hasn't watched are in ([seen]; the rows keep this order and length, so the app can leave watched titles out, its
 * own new marks too, and still show 60).
 */
export function trueScoreRows(seen: Set<string>, films: Store): [string, string[]][] {
  return ([['ts-recent', 'adjusted'], ['ts-all', 'score']] as const).map(([row, by]) =>
    [row, untilUnseen([...films].filter(([, f]) => f[by] !== undefined).sort(([, a], [, b]) => b[by]! - a[by]!).map(([id]) => id), seen)])
}

/**
 * The TrueScore shows row by id: IMDb's Top 250 and Most Popular TV shows ([shows]) by TrueScore's net score on IMDb's histogram
 * ([scoreTitles]' [s]; a show without one last, in IMDb's order), until 90 the owner hasn't watched are in, like [trueScoreRows].
 */
export function trueScoreShows(shows: string[], scores: Map<string, Score>, seen: Set<string>): string[] {
  const s = (id: string) => scores.get(id)?.s ?? -Infinity
  return untilUnseen(shows.slice().sort((a, b) => s(b) - s(a)), seen)
}

/**
 * Every cached title's score: conviction from Letterboxd's histogram where TrueScore has one, else IMDb's (loved
 * 4½–5★ or 9–10, hated ½–1★ or 1–2: the same buckets), and each source's average shrunk toward 6.5 by its prior,
 * averaged by votes. The histogram gives the net share ((loved − hated) / total); its weight is the larger crowd of
 * IMDb's votes and the histogram's, so a Letterboxd-ranked film sits on the same scale as an IMDb-ranked one
 * (share × |share| × votes, which is [netScore] when the histogram is IMDb's own). A film in TrueScore's store also carries
 * what its page shows: [ta] the adjusted score, [tr] the all-time net-loved ratio, [tq] the recent one (adjusted / |all-time|).
 */
export function scoreTitles(cache: ScoreCache, lbxFilms: Store): Map<string, Score> {
  const out = new Map<string, Score>()
  for (const id of new Set([...Object.keys(cache), ...lbxFilms.keys()])) {
    const e = cache[id] ?? {}
    const src = ([[e.ir, e.iv, 3000], [e.lb && e.lb * 2, e.lv, 1000]] as number[][]).filter(([r, v]) => r && v)
    const votes = sum(src.map(([, v]) => v))
    const raw = votes && sum(src.map(([r, v, m]) => (v * (v * r + m * 6.5)) / (v + m))) / votes
    const shown = { ...(e.ir && { ir: e.ir }), ...(e.iv && { iv: e.iv }), ...(e.lb && { lb: e.lb }), ...(e.lv && { lv: e.lv }) }
    const lf = lbxFilms.get(id), h = lf?.h ?? e.h, total = h ? sum(h) : 0
    // TrueScore's adjusted score (all-time × its newest reviews' net-loved ratio), else its all-time one, else a histogram's, else IMDb's.
    if (lf?.adjusted !== undefined) out.set(id, { s: lf.adjusted })
    else if (lf?.score !== undefined) out.set(id, { s: lf.score })
    else if (total) {
      const share = netShare(h!)
      out.set(id, { s: Math.round(share * Math.abs(share) * Math.max(total, e.iv ?? 0)) })
    }
    const r3 = (x: number) => Math.round(x * 1000) / 1000
    if (lf?.ratio !== undefined) out.set(id, { ...out.get(id), tr: r3(lf.ratio), ...(lf.adjusted !== undefined && { ta: lf.adjusted, ...(lf.score && { tq: r3(lf.adjusted / Math.abs(lf.score)) }) }) })
    if (raw || Object.keys(shown).length) out.set(id, { ...out.get(id), ...(raw && { s10: Math.round(raw * 10) / 10 }), ...shown })
  }
  return out
}

/**
 * A title page's net-loved share ([netShare], 3 places) [hn] from the histogram [scoreTitles] scored it on, when
 * TrueScore's store has no score of its own for it, so the page can show TrueScore's line without the recent trend; [hs] says
 * whose: 'l' Letterboxd's (from the store), 'i' IMDb's. {} when there is no histogram.
 */
export function histogramShare(id: string, cache: ScoreCache, films: Store): { hn?: number; hs?: 'l' | 'i' } {
  const lf = films.get(id)
  if (lf?.ratio !== undefined || lf?.score !== undefined || lf?.adjusted !== undefined) return {}
  const h = lf?.h ?? cache[id]?.h
  if (!h || !sum(h)) return {}
  return { hn: Math.round(netShare(h) * 1000) / 1000, hs: lf?.h ? 'l' : 'i' }
}
