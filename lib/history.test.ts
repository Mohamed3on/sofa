import { afterEach, expect, spyOn, test } from 'bun:test'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { editHistory, followed, keepRefreshed, loadHistory, nextEpisodes, refreshHistory, type Kept } from './history'
import { resetTrakt } from './trakt'

const none: Kept = { at: 0, trakt: 'off', letterboxd: 'off', lb: [], movies: [], eps: [], shows: {}, hidden: [], watchlist: [] }

const env = { ...process.env }
afterEach(() => {
  process.env = { ...env }
  resetTrakt()
})

const rss = readFileSync(join(import.meta.dir, 'fixtures/letterboxd-rss.xml'), 'utf8')

/** Signed in to Trakt (a token good for a day), Letterboxd user set, and every call answered by [answer]. */
function sources(answer: (url: URL) => Response) {
  const file = join(mkdtempSync(join(tmpdir(), 'history-')), 'token.json')
  writeFileSync(file, JSON.stringify({ access: 'A', refresh: 'R', at: Date.now(), expires: Date.now() + 864e5, user: 'mo' }))
  process.env.TRAKT_TOKEN_FILE = file
  process.env.LETTERBOXD_USER = 'mohamed3on'
  return spyOn(globalThis, 'fetch').mockImplementation((async (url: string) => answer(new URL(url))) as unknown as typeof fetch)
}

// Answers in the shape of Trakt's API docs (sync/watched, sync/watchlist, recommendations).
const trakt = (path: string): Response => {
  if (path === '/sync/last_activities') return Response.json({ all: '2026-11-05T10:00:00.000Z' })
  if (path === '/sync/watched/movies') return Response.json([{ plays: 1, last_watched_at: '2026-10-01T20:00:00.000Z', movie: { title: 'Inception', year: 2010, ids: { trakt: 16662, tmdb: 27205 } } }])
  if (path === '/sync/watched/shows') return Response.json([{ plays: 2, last_watched_at: '2026-10-20T20:00:00.000Z', show: { title: 'Severance', ids: { trakt: 1, tmdb: 95396 } }, seasons: [{ number: 1, episodes: [{ number: 1, plays: 1, last_watched_at: '2026-10-19T20:00:00.000Z' }, { number: 2, plays: 1, last_watched_at: '2026-10-20T20:00:00.000Z' }] }] }])
  if (path === '/users/hidden/progress_watched' || path === '/sync/ratings') return Response.json([])
  if (path === '/sync/watchlist') return Response.json([{ rank: 1, type: 'movie', movie: { title: 'Dune', ids: { tmdb: 438631 } } }, { rank: 2, type: 'show', show: { title: 'Shogun', ids: { tmdb: 126308 } } }])
  if (path.startsWith('/recommendations/')) return Response.json(path.endsWith('movies') ? [{ title: 'Heat', ids: { tmdb: 949 } }] : [])
  return new Response('', { status: 404 })
}

test('a Letterboxd RSS item with tmdb:movieId 603 marks movie:603 watched', async () => {
  const fetch = sources((u) => (u.hostname !== 'letterboxd.com' ? trakt(u.pathname) : new Response(u.pathname.includes('/watchlist/') ? '<title>Watchlist &bull; Letterboxd</title>' : rss)))
  try {
    const h = await refreshHistory(null)
    expect(h.letterboxd).toBe('on')
    expect(h.lb).toContain('movie:603')
    expect(h.trakt).toBe('on')
    expect(h.eps).toEqual(['tv:95396:s1e2', 'tv:95396:s1e1']) // the latest watched first
  } finally {
    fetch.mockRestore()
  }
})

test('a blocked Letterboxd feed leaves the Trakt rows working and sets its status, keeping the films it gave before', async () => {
  const fetch = sources((u) => (u.hostname === 'letterboxd.com' ? new Response('<html>Just a moment...</html>', { status: 403 }) : trakt(u.pathname)))
  try {
    const h = await refreshHistory({ ...none, letterboxd: 'on', lb: ['movie:603'] })
    expect(h.letterboxd).toBe('blocked')
    expect(h.lb).toEqual(['movie:603'])
    expect(h.trakt).toBe('on')
    expect(h.watchlist).toEqual(['movie:438631', 'tv:126308'])
  } finally {
    fetch.mockRestore()
  }
})

test("a show whose next episode aired yesterday enters Up next as a new episode", async () => {
  const now = Date.now()
  const day = (ago: number) => new Date(now - ago * 864e5).toISOString().slice(0, 10)
  // Trakt saw S1 E1–E2 a week ago; the stick finished E3 five days ago; E4 aired yesterday.
  const h: Kept = { ...none, trakt: 'on', eps: ['tv:95396:s1e2', 'tv:95396:s1e1'], shows: { 'tv:95396': now - 7 * 864e5 } }
  const follow = followed(h, [`tv:95396:s1e3@${now - 5 * 864e5}`], now)
  const ep = (e: number, ago: number) => ({ season: 1, episode: e, title: `E${e}`, secs: 0, image: '', air: day(ago) })
  const next = await nextEpisodes(follow, async () => [ep(1, 30), ep(2, 23), ep(3, 16), ep(4, 1)])
  expect(next).toEqual([{ show: 'tv:95396', ep: 'tv:95396:s1e4', c: 'S1 E4', en: 'E4', air: day(1), new: 1 }])
  // Caught up: nothing.
  expect(await nextEpisodes(follow, async () => [ep(1, 30), ep(2, 23), ep(3, 16)])).toEqual([])
})

test('marks and watchlist changes made during a refresh survive it', async () => {
  process.env.HISTORY_FILE = join(mkdtempSync(join(tmpdir(), 'history-')), 'history.json')
  writeFileSync(process.env.HISTORY_FILE, JSON.stringify({ ...none, movies: ['movie:1'] }))
  const started = Date.now()
  // while Trakt is read: the stick marks a film, rates one and adds a show to the watchlist
  await editHistory({ title: 'movie:2', watched: true })
  await editHistory({ title: 'movie:1', rating: 8 })
  await editHistory({ title: 'tv:3', on: true, runtime: 45 })
  // the refresh, read from Trakt before those landed there
  const h = await keepRefreshed({ ...none, at: Date.now(), movies: ['movie:1', 'movie:9'] }, started)
  expect([h.movies, h.rated, h.watchlist, h.runtimes]).toEqual([['movie:2', 'movie:1', 'movie:9'], { 'movie:1': 8 }, ['tv:3'], { 'tv:3': 45 }])
  expect(await loadHistory()).toEqual(h)
})
