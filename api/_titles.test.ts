import { expect, spyOn, test } from 'bun:test'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { decorate, fromDetails, fromRecommendation, fromTmdb, fromVod, providerCard, titleCard, type Known } from './_titles'
import type { TmdbTitle } from './_tmdb'

test('every source gives a film or show the same card: id, kind, name, year, poster', () => {
  expect(fromTmdb('movie', { id: 27205, title: 'Inception', release_date: '2010-07-15', poster_path: '/i.jpg', backdrop_path: '/b.jpg', original_language: 'en', genre_ids: [28], popularity: 80 }))
    .toEqual({ id: 'movie:27205', k: 'm', n: 'Inception', y: 2010, p: '/i.jpg', b: '/b.jpg', l: 'en', g: [28], pp: 80 })
  expect(fromTmdb('tv', { id: 1396, name: 'Breaking Bad', first_air_date: '2008-01-20', genres: [{ id: 18 }] }))
    .toEqual({ id: 'tv:1396', k: 't', n: 'Breaking Bad', y: 2008, g: [18] })
  const details = { name: 'Breaking Bad', year: '2008', poster: 'https://image.tmdb.org/t/p/w342/p.jpg', backdrop: '/b.jpg', originalLanguage: 'en' } as TmdbTitle
  expect(fromDetails('tv:1396', details)).toEqual({ id: 'tv:1396', k: 't', n: 'Breaking Bad', y: 2008, p: 'https://image.tmdb.org/t/p/w342/p.jpg', b: '/b.jpg', l: 'en' })
  expect(fromRecommendation({ title: 'movie:155', name: 'The Dark Knight', year: '2008', poster: '' })).toEqual({ id: 'movie:155', k: 'm', n: 'The Dark Knight', y: 2008 })
  expect(fromVod({ id: 5, titleId: 'iptv:5', name: 'EN - Making Of', poster: 'http://p/x.png', group: 'g', ext: 'mkv' })).toEqual({ id: 'iptv:5', k: 'm', n: 'EN - Making Of', p: 'http://p/x.png' })
  // The provider's posters on TMDB's host keep only the path, so the app picks the size.
  expect(providerCard('movie:1', 'm', 'F1', 'https://image.tmdb.org/t/p/w600_and_h900_bestv2/f.jpg')).toEqual({ id: 'movie:1', k: 'm', n: 'F1', p: '/f.jpg' })
  expect(providerCard('tv:2', 't', 'S2', '')).toEqual({ id: 'tv:2', k: 't', n: 'S2' })
})

test('a card carries the tier the library knows for it, its score and its kept runtime', () => {
  const k: Known = {
    lib: null,
    titles: new Map([['movie:1', { id: 'movie:1', k: 'm', n: 'F1', t: 'Reference', e: 1, rt: 120 }], ['movie:2', { id: 'movie:2', k: 'm', n: 'F2' }]]),
    scores: new Map([['movie:2', { s: 700, s10: 7.9 }]]),
    rt: (id) => (id === 'movie:2' ? 95 : undefined),
  }
  expect(decorate(k, { id: 'movie:1', k: 'm', n: 'F1' })).toEqual({ id: 'movie:1', k: 'm', n: 'F1', t: 'Reference', e: 1, rt: 120 })
  expect(decorate(k, { id: 'movie:2', k: 'm', n: 'F2' })).toEqual({ id: 'movie:2', k: 'm', n: 'F2', s: 700, s10: 7.9, rt: 95 })
  expect(decorate(k, { id: 'movie:3', k: 'm', n: 'F3', rt: 80 })).toEqual({ id: 'movie:3', k: 'm', n: 'F3', rt: 80 })
})

test('a title looked up by id gets the card a TMDB list would give it, from the kept details; an adult one none', async () => {
  const env = { ...process.env }
  process.env.TMDB_TOKEN = '0123456789abcdef0123456789abcdef'
  process.env.TMDB_CACHE_FILE = join(mkdtempSync(join(tmpdir(), 'tmdb-')), 'tmdb-titles.json')
  const details = { id: 900001, title: 'Inception', release_date: '2010-07-15', poster_path: '/i.jpg', backdrop_path: '/b.jpg', original_language: 'en', genres: [{ id: 28, name: 'Action' }], popularity: 80 }
  const fetchSpy = spyOn(globalThis, 'fetch').mockImplementation((async (url: string) =>
    Response.json(url.includes('/movie/900001?') ? details : { ...details, id: 900002, adult: true })) as typeof fetch)
  try {
    const card = await titleCard('movie:900001')
    expect(card).toEqual(fromTmdb('movie', details))
    expect(card).toEqual({ id: 'movie:900001', k: 'm', n: 'Inception', y: 2010, p: '/i.jpg', b: '/b.jpg', l: 'en', g: [28], pp: 80 })
    expect(await titleCard('movie:900002')).toBeNull()
    expect(await titleCard('tv:1:s1e2')).toBeNull()
  } finally {
    fetchSpy.mockRestore()
    process.env = env
  }
})
