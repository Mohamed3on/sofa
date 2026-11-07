import { afterEach, expect, spyOn, test } from 'bun:test'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { episodeTitle, filmTitle, showTitle, tmdbFacts, tmdbTitle } from './tmdb'
import movie from './fixtures/tmdb-movie-27205.json'
import tv from './fixtures/tmdb-tv-1396.json'

const env = { ...process.env }
afterEach(() => {
  process.env = { ...env }
})

test('title ids: TMDB keys merge copies, provider-only titles never do', () => {
  expect(filmTitle('1378537', 9)).toBe('movie:1378537')
  expect(filmTitle('', 5)).toBe('iptv:5')
  expect(filmTitle('0', 6)).toBe('iptv:6')
  expect(showTitle(undefined, 5)).toBe('iptv:tv:5')
  expect(episodeTitle(showTitle('1396', 2), 1, 3)).toBe('tv:1396:s1e3')
})

test('without TMDB_TOKEN nothing is fetched and titles carry no TMDB facts', async () => {
  delete process.env.TMDB_TOKEN
  const fetchSpy = spyOn(globalThis, 'fetch')
  try {
    expect(await tmdbTitle('movie', 27205)).toBeNull()
    expect(await tmdbFacts('movie:27205')).toEqual({})
    expect(fetchSpy).not.toHaveBeenCalled()
  } finally {
    fetchSpy.mockRestore()
  }
})

test('details come from TMDB once, then from the cache file', async () => {
  process.env.TMDB_TOKEN = '0123456789abcdef0123456789abcdef'
  process.env.TMDB_CACHE_FILE = join(mkdtempSync(join(tmpdir(), 'tmdb-')), 'tmdb-titles.json')
  const fetchSpy = spyOn(globalThis, 'fetch').mockImplementation((async (url: string) =>
    url.includes('/movie/27205?') ? Response.json(movie) : url.includes('/tv/1396?') ? Response.json(tv) : new Response('', { status: 404 })) as typeof fetch)
  try {
    expect(await tmdbTitle('movie', '27205')).toMatchObject({
      imdb: 'tt1375666', originalLanguage: 'en', runtime: 148, name: 'Inception', year: '2010',
      directors: [{ id: 525, name: 'Christopher Nolan' }],
      recommendations: [{ title: 'movie:9620', name: 'Paycheck', year: '2003' }, { title: 'movie:353491' }],
    })
    // A v3 key rides in the query; details, IMDb id, credits and recommendations come in one call.
    expect(String(fetchSpy.mock.calls[0][0])).toContain('append_to_response=external_ids,credits,recommendations,videos&')
    // A show: IMDb id from external_ids, runtime from its latest episode, creators as directors.
    expect(await tmdbFacts('tv:1396:s1e3')).toEqual({ imdb: 'tt0903747', originalLanguage: 'en' })
    expect(await tmdbTitle('tv', 1396)).toMatchObject({ runtime: 56, directors: [{ name: 'Vince Gilligan' }] })
    expect(await tmdbTitle('movie', 1)).toBeNull()
    expect(fetchSpy).toHaveBeenCalledTimes(3)
    await Bun.sleep(50)
    expect(Object.keys(await Bun.file(process.env.TMDB_CACHE_FILE).json())).toEqual(expect.arrayContaining(['movie:27205', 'tv:1396', 'movie:1'])) // other files' titles share the in-memory cache
  } finally {
    fetchSpy.mockRestore()
  }
})
