// Scores for the library (KTD16). Rows sort by [s], TrueScore's (recent-adjusted, else all-time) score where its store on the box has one
// ([letterboxdFilms]), else its conviction on Letterboxd's histogram from there, else on IMDb's 1–10 one (loved 9–10
// minus hated 1–2, weighted by that net share); titles without one follow, by [s10], and a title with neither sorts
// at its row's median. [s10] is IMDb's and Letterboxd's averages (MDBList), each pulled toward 6.5/10 by a Bayesian
// prior (3,000 votes for IMDb, 1,000 for Letterboxd), averaged by votes. Answers are kept in .cache/scores.json:
// MDBList monthly (200 titles a request at night; it also gives the IMDb id), IMDb's GraphQL weekly (100 a request),
// both daily for new releases; a title page asks whatever is due at once ([scoreNow]). scripts/build-library.ts
// runs the nightly part.
import { readFileSync, statSync, watch } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { basename, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { writeAtomic } from './_fs.js'
import { parseTitleId, tmdbGet } from './_tmdb.js'

/**
 * What was said about one title, times in ms: IMDb average (/10) and votes, Letterboxd average (0–5) and votes,
 * when MDBList was asked; the IMDb id, IMDb's votes for 1 to 10, and when IMDb was asked.
 */
export interface ScoreEntry { ir?: number; iv?: number; lb?: number; lv?: number; ma?: number; im?: string; h?: number[]; ha?: number }
export type ScoreCache = Record<string, ScoreEntry>
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
/** MDBList requests kept each day for title pages ([scoreNow]); the nightly job stops before them. */
const ONDEMAND = Number(process.env.MDBLIST_ONDEMAND) || 200
const DAY = 864e5
const MDB = 'https://api.mdblist.com'

const sum = (d: number[]) => d.reduce((a, b) => a + b, 0)

/** TrueScore's net score: net × (net / total), sign kept. */
export const netScore = (net: number, total: number) => (total > 0 ? Math.sign(net) * Math.round((net * net) / total) : 0)

const LBX_FILE = process.env.LETTERBOXD_HISTOGRAMS || '/var/lib/truescore/letterboxd.json'
type LbxFilm = { h?: number[]; score?: number; adjusted?: number; ratio?: number }
const lbx = { at: 0, mtime: 0, watched: false, byId: new Map<string, LbxFilm>() }
/**
 * TrueScore's score and Letterboxd's histogram (½★ to 5★) by movie:<tmdb>, from TrueScore's store on this box (what
 * its extension saw on letterboxd.com), the busiest histogram per id; read again when the store changes ([watchStore],
 * else looked at once a minute), the last one kept while the file can't be read.
 */
export function letterboxdFilms(now = Date.now()) {
  if (!lbx.watched && now - lbx.at >= 60e3) (lbx.at = now), readStore()
  return lbx.byId
}
/**
 * When the store [letterboxdFilms] holds was written (ms; 0 before any), the file looked at first: a library request
 * catches what the watch can miss (Bun's reports writes and renames, not a bare mtime change).
 */
export const storeAt = () => (letterboxdFilms(), readStore(), lbx.mtime)

/** Reads the store when it changed since the last read; true when it did. */
function readStore() {
  try {
    const mtime = statSync(LBX_FILE).mtimeMs
    if (mtime === lbx.mtime) return false
    const byId = new Map<string, LbxFilm>()
    for (const { h, score, adjusted, ratio, tmdb } of Object.values(JSON.parse(readFileSync(LBX_FILE, 'utf8')) as Record<string, LbxFilm & { tmdb?: number | null }>)) {
      if (!tmdb) continue
      const id = `movie:${tmdb}`, was = byId.get(id) ?? {}
      byId.set(id, { score: score ?? was.score, adjusted: adjusted ?? was.adjusted, ratio: ratio ?? was.ratio, h: h && sum(h) > sum(was.h ?? []) ? h : was.h })
    }
    lbx.byId = byId
    lbx.mtime = mtime
    return true
  } catch { return false /* the last read stands */ }
}

/**
 * Reads TrueScore's store 1 s after each change (its folder watched, as a write may replace the file by a rename),
 * then calls [changed]; when the watch fails, [letterboxdFilms] looks at the file once a minute instead. Server only.
 */
export function watchStore(changed: () => void) {
  letterboxdFilms()
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    const w = watch(dirname(LBX_FILE), (_, name) => {
      if (name && name !== basename(LBX_FILE)) return
      clearTimeout(timer)
      timer = setTimeout(() => readStore() && changed(), 1000)
    })
    w.on('error', () => ((lbx.watched = false), w.close()))
    w.unref?.()
    lbx.watched = true
  } catch { /* the minute look stands */ }
}

/** One title's score as it is now (TrueScore's store as last read). */
export function scoreOf(id: string, cache: ScoreCache = {}) {
  const f = letterboxdFilms().get(id)
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
export function trueScoreRows(seen: Set<string>, films = letterboxdFilms()): [string, string[]][] {
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

const TOP_TV_Q = 'query { top: chartTitles(first: 250, chart: { chartType: TOP_RATED_TV_SHOWS }) { edges { node { id } } } popular: chartTitles(first: 100, chart: { chartType: MOST_POPULAR_TV_SHOWS }) { edges { node { id } } } }'
/**
 * IMDb's Top 250 TV shows, then its 100 Most Popular (imdb.com/chart/tvmeter) not among them, as tv:<tmdb> ids: each IMDb id matched through [cache]'s, else TMDB's
 * find (then kept in [cache], which also lets [refreshScores] ask IMDb for its histogram). Empty when IMDb doesn't answer.
 */
export async function imdbTopShows(cache: ScoreCache, get: typeof fetch = fetch): Promise<string[]> {
  const r = await get('https://caching.graphql.imdb.com/', { method: 'POST', body: JSON.stringify({ query: TOP_TV_Q }), headers: IMDB_HEADERS })
    .then((r) => (r.ok ? (r.json() as Promise<{ data?: Record<'top' | 'popular', { edges?: { node: { id: string } }[] } | null> }>) : null), () => null)
  const tts = [...new Set([...(r?.data?.top?.edges ?? []), ...(r?.data?.popular?.edges ?? [])].map((e) => e.node.id))].filter((tt) => TT.test(tt))
  const byTt = new Map(Object.entries(cache).flatMap(([id, e]) => (id.startsWith('tv:') && e.im ? [[e.im, id]] : [])))
  const ids = await Promise.all(tts.map(async (tt) => {
    if (byTt.has(tt)) return byTt.get(tt)!
    const found = await tmdbGet<{ tv_results?: { id: number }[] }>(`find/${tt}?external_source=imdb_id`).catch(() => null)
    const n = found?.tv_results?.[0]?.id
    if (!n) return null
    cache[`tv:${n}`] = { ...cache[`tv:${n}`], im: tt }
    return `tv:${n}`
  }))
  return [...new Set(ids.filter((id): id is string => !!id))]
}

/**
 * Every cached title's score: conviction from Letterboxd's histogram where TrueScore has one, else IMDb's (loved
 * 4½–5★ or 9–10, hated ½–1★ or 1–2: the same buckets), and each source's average shrunk toward 6.5 by its prior,
 * averaged by votes. The histogram gives the net share ((loved − hated) / total); its weight is the larger crowd of
 * IMDb's votes and the histogram's, so a Letterboxd-ranked film sits on the same scale as an IMDb-ranked one
 * (share × |share| × votes, which is [netScore] when the histogram is IMDb's own). A film in TrueScore's store also carries
 * what its page shows: [ta] the adjusted score, [tr] the all-time net-loved ratio, [tq] the recent one (adjusted / |all-time|).
 */
export function scoreTitles(cache: ScoreCache, lbxFilms = letterboxdFilms()): Map<string, Score> {
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
      const share = (h![8] + h![9] - h![0] - h![1]) / total
      out.set(id, { s: Math.round(share * Math.abs(share) * Math.max(total, e.iv ?? 0)) })
    }
    const r3 = (x: number) => Math.round(x * 1000) / 1000
    if (lf?.ratio !== undefined) out.set(id, { ...out.get(id), tr: r3(lf.ratio), ...(lf.adjusted !== undefined && { ta: lf.adjusted, ...(lf.score && { tq: r3(lf.adjusted / Math.abs(lf.score)) }) }) })
    if (raw || Object.keys(shown).length) out.set(id, { ...out.get(id), ...(raw && { s10: Math.round(raw * 10) / 10 }), ...shown })
  }
  return out
}

/**
 * A title page's net-loved share ((loved − hated) / total, 3 places) [hn] from the histogram [scoreTitles] scored it on, when
 * TrueScore's store has no score of its own for it, so the page can show TrueScore's line without the recent trend; [hs] says
 * whose: 'l' Letterboxd's (from the store), 'i' IMDb's. {} when there is no histogram.
 */
export function histogramShare(id: string, cache: ScoreCache = {}): { hn?: number; hs?: 'l' | 'i' } {
  const lf = letterboxdFilms().get(id)
  if (lf?.ratio !== undefined || lf?.score !== undefined || lf?.adjusted !== undefined) return {}
  const h = lf?.h ?? cache[id]?.h, total = h ? sum(h) : 0
  if (!total) return {}
  return { hn: Math.round(((h![8] + h![9] - h![0] - h![1]) / total) * 1000) / 1000, hs: lf?.h ? 'l' : 'i' }
}

interface Mdb { ids?: { tmdb?: number; imdb?: string }; ratings?: { source: string; value?: number | null; score?: number | null; votes?: number | null }[] }
/** MDBList's answer for a title (Letterboxd's 0–100 score as 0–5). */
function entry(m: Mdb | undefined, now: number): ScoreEntry {
  const of = (source: string) => m?.ratings?.find((x) => x.source === source)
  const im = of('imdb'), lb = of('letterboxd')
  return { ma: now, ...(m?.ids?.imdb && { im: m.ids.imdb }), ...(im?.value && { ir: im.value }), ...(im?.votes && { iv: im.votes }), ...(lb?.score && { lb: Math.round(lb.score * 5) / 100 }), ...(lb?.votes && { lv: lb.votes }) }
}

/** An IMDb title id (some lookups give others, which fail IMDb's whole batch). */
const TT = /^tt\d+$/
const IMDB_HEADERS = { 'content-type': 'application/json', 'x-imdb-client-name': 'imdb-web-next-localized', 'user-agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36' }
const IMDB_Q = 'query($ids: [ID!]!) { titles(ids: $ids) { id ratingsSummary { aggregateRating voteCount } aggregateRatingsBreakdown { histogram { histogramValues { rating voteCount } } } } }'
/** IMDb's answers for the titles [ims] by IMDb id (none for one IMDb doesn't know), or null when IMDb didn't answer. */
async function imdb(ims: string[], now: number, get: typeof fetch, signal?: AbortSignal): Promise<Map<string, ScoreEntry> | null> {
  type T = { id: string; ratingsSummary?: { aggregateRating?: number | null; voteCount?: number | null }; aggregateRatingsBreakdown?: { histogram?: { histogramValues?: { rating: number; voteCount: number }[] } | null } } | null
  const r = await get('https://caching.graphql.imdb.com/', {
    method: 'POST', signal, body: JSON.stringify({ query: IMDB_Q, variables: { ids: ims } }),
    headers: IMDB_HEADERS,
  }).then((r) => (r.ok ? (r.json() as Promise<{ data?: { titles?: T[] } }>) : null), () => null)
  if (!r?.data?.titles) return null
  return new Map(r.data.titles.filter((t) => !!t).map((t) => {
    const h = Array<number>(10).fill(0)
    for (const { rating, voteCount } of t.aggregateRatingsBreakdown?.histogram?.histogramValues ?? []) if (rating >= 1 && rating <= 10) h[rating - 1] = voteCount || 0
    const { aggregateRating: ir, voteCount: iv } = t.ratingsSummary ?? {}
    return [t.id, { ha: now, ...(sum(h) && { h }), ...(ir && { ir }), ...(iv && { iv }) }]
  }))
}

/**
 * Asks MDBList, then IMDb, about the titles in [ids] (in that order, the order the app shows them in) whose answers
 * in [cache] are due: MDBList monthly, IMDb weekly, both daily for [fresh] titles. MDBList takes 200 titles a request
 * and stops when it refuses or only the title pages' share of the day's quota is left; IMDb takes 100, two requests
 * a second, backing off 30 s (doubling) when it fails, and stops for the night after the third failure in a row.
 * Updates [cache] in place; returns the titles each answered.
 */
export async function refreshScores(ids: string[], fresh: Set<string>, cache: ScoreCache, key: string | undefined, opts: { fetch?: typeof fetch; now?: number; save?: () => Promise<void>; gap?: number } = {}) {
  const { fetch: get = fetch, now = Date.now(), save, gap = 500 } = opts
  const counts = { mdblist: 0, imdb: 0 }
  const mdbDue = key ? ids.flatMap((id) => {
    const p = parseTitleId(id)
    return p && p.season == null && !(cache[id]?.ma! > now - (fresh.has(id) ? 1 : 30) * DAY) ? [{ id, type: p.kind === 'tv' ? 'show' : 'movie', n: p.tmdb }] : []
  }) : []
  mdb: for (let i = 0; i < mdbDue.length; i += 200) {
    for (const type of ['movie', 'show']) {
      const batch = mdbDue.slice(i, i + 200).filter((t) => t.type === type)
      if (!batch.length) continue
      const r = await get(`${MDB}/tmdb/${type}?apikey=${key}`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ ids: batch.map((t) => String(t.n)) }) }).catch(() => null)
      const found = r?.ok ? ((await r.json()) as Mdb[]) : null
      if (!Array.isArray(found)) { console.warn(`scores: MDBList ${r?.status ?? 'unreachable'}; the rest wait for tomorrow`); break mdb }
      const byTmdb = new Map(found.map((m) => [String(m.ids?.tmdb), m]))
      for (const t of batch) cache[t.id] = { ...cache[t.id], ...entry(byTmdb.get(t.n), now) }
      counts.mdblist += batch.length
      await save?.()
      if (Number(r!.headers.get('x-ratelimit-remaining') ?? Infinity) <= ONDEMAND) break mdb
    }
  }
  const imDue = ids.filter((id) => TT.test(cache[id]?.im ?? '') && !(cache[id].ha! > now - (fresh.has(id) ? 1 : 7) * DAY))
  for (let i = 0, fails = 0; i < imDue.length && fails < 3;) {
    const batch = imDue.slice(i, i + 100)
    const found = await imdb(batch.map((id) => cache[id].im!), now, get)
    if (!found) { await Bun.sleep(gap * 60 * 2 ** fails++); continue }
    for (const id of batch) cache[id] = { ...cache[id], ...(found.get(cache[id].im!) ?? { ha: now }) }
    counts.imdb += batch.length
    i += 100, fails = 0
    await save?.()
    await Bun.sleep(gap)
  }
  if (imDue.length > counts.imdb) console.warn(`scores: IMDb failing; ${imDue.length - counts.imdb} titles wait for tomorrow`)
  return counts
}

export const SCORES_FILE = fileURLToPath(new URL('../.cache/scores.json', import.meta.url))
const OLD = new Set(['tk', 'd', 'ta']) // Trakt's answers, kept by older builds
export const loadScores = (file = SCORES_FILE): Promise<ScoreCache> => readFile(file, 'utf8').then((s) => JSON.parse(s, (k, v) => (OLD.has(k) ? undefined : v)) as ScoreCache, () => ({}))
/**
 * Writes [cache] atomically (temp + rename), merged over the file's entries field by field, so the nightly job
 * keeps what title pages asked meanwhile ([scoreNow]) and a title page's ask keeps the night's work.
 */
export const saveScores = async (cache: ScoreCache, file = SCORES_FILE) => {
  const disk = await loadScores(file)
  for (const id in cache) disk[id] = { ...disk[id], ...cache[id] }
  return writeAtomic(file, disk)
}

let mdbDay = '', mdbLeft = 0, saving = Promise.resolve()
const asking = new Map<string, Promise<ScoreEntry | null>>()

/** Whether [e]'s MDBList (monthly) or IMDb (weekly) answer is due. */
export const due = (e: ScoreEntry | undefined, now = Date.now()) => ({ mdb: !(e?.ma! > now - 30 * DAY), imdb: !(e?.ha! > now - 7 * DAY) })

/**
 * What MDBList and IMDb say about [id] (movie:N or tv:N, IMDb id [im]) now, for a title page, asking only what
 * [have] has due: in parallel, each cut at 3 s; MDBList at most MDBLIST_ONDEMAND (200) a day, none after a refusal.
 * Saved into scores.json; null when neither answered.
 */
export function scoreNow(id: string, im: string | undefined, key: string | undefined, have?: ScoreEntry, get: typeof fetch = fetch): Promise<ScoreEntry | null> {
  const p = parseTitleId(id)
  if (!p || p.season != null) return Promise.resolve(null)
  if (asking.has(id)) return asking.get(id)!
  const now = Date.now(), day = new Date(now).toISOString().slice(0, 10), d = due(have, now)
  if (mdbDay !== day) [mdbDay, mdbLeft] = [day, ONDEMAND]
  const mdb = async (): Promise<ScoreEntry> => {
    if (!key || !d.mdb || mdbLeft-- <= 0) return {}
    const r = await get(`${MDB}/tmdb/${p.kind === 'tv' ? 'show' : 'movie'}/${p.tmdb}?apikey=${key}`, { signal: AbortSignal.timeout(3000) })
    if (r.status === 429 || r.status === 401 || r.status === 403 || r.headers.get('x-ratelimit-remaining') === '0') mdbLeft = 0
    return r.ok || r.status === 404 ? entry(r.ok ? ((await r.json()) as Mdb) : undefined, now) : {}
  }
  const ask = async (): Promise<ScoreEntry> => {
    const tt = im ?? have?.im
    const found = tt && TT.test(tt) && d.imdb ? await imdb([tt], now, get, AbortSignal.timeout(3000)) : null
    return found ? found.get(tt!) ?? { ha: now } : {}
  }
  const job = Promise.all([mdb().catch(() => ({})), ask().catch(() => ({}))]).then(([a, b]) => {
    const e: ScoreEntry = { ...a, ...b, ...((im ?? a.im) && { im: im ?? a.im }) }
    if (!e.ma && !e.ha) return null
    saving = saving.then(() => saveScores({ [id]: e })).catch((err) => console.warn(`scores: ${err}`))
    return e
  }).finally(() => asking.delete(id))
  asking.set(id, job)
  return job
}
