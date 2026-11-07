import { expect, test } from 'bun:test'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { assemble, rebuildLibrary, rowDefs, servedLibrary, showLibrary, type Library } from './library'
import { scoreTitles } from './scores'
import { fromTmdb, type TmdbItem } from './titles'

test('films land in their language rows (ko under Other, English in none), provider tiers join TMDB titles, a title with no copy has none', () => {
  const defs = rowDefs(new Date('2026-11-05'))
  const film = (id: number, lang: string) => fromTmdb('movie', { id, title: `F${id}`, original_language: lang, poster_path: `/${id}.jpg`, genre_ids: [35] })
  const seeds = defs.map((d) => (d.id === 'trending' ? [film(1, 'en'), film(2, 'ar'), film(3, 'ru'), film(4, 'ko')] : []))
  const lib = assemble(defs, seeds, [{ t: { id: 'movie:1', k: 'm', n: 'F1', t: 'Reference', e: 1 }, group: '🎬 NETFLIX MOVIES 4K' }], null, {})
  const row = (id: string) => lib.rows.find((r) => r.id === id)!.i.map((i) => lib.titles[i].id)
  expect(lib.rows.some((r) => r.id === 'en')).toBe(false)
  expect(row('ar')).toEqual(['movie:2'])
  expect(row('ru')).toEqual(['movie:3'])
  expect(row('other')).toEqual(['movie:4'])
  expect(row('comedy')).toEqual(['movie:1', 'movie:2', 'movie:3', 'movie:4'])
  expect(lib.titles.find((t) => t.id === 'movie:1')).toMatchObject({ p: '/1.jpg', t: 'Reference', e: 1 })
  expect(lib.titles.find((t) => t.id === 'movie:2')!.t).toBeUndefined()
  // A tier measured in an earlier build beats this build's claim.
  const next = assemble(defs, seeds, [{ t: { id: 'movie:1', k: 'm', n: 'F1', t: 'Reference', e: 1 }, group: 'x' }], { ...lib, titles: [{ id: 'movie:1', k: 'm', n: 'F1', t: 'Great' }] }, {})
  expect(next.titles.find((t) => t.id === 'movie:1')).toMatchObject({ t: 'Great' })
  expect(next.titles.find((t) => t.id === 'movie:1')!.e).toBeUndefined()
})

test('the endpoint serves library.json while fresh, else the provider rows', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'library-'))
  const provider = async () => ({ src: 'provider' } as Library)
  const missing = await servedLibrary(provider, provider, () => 0, join(dir, 'none.json'))
  expect(JSON.parse(Buffer.from(missing.body).toString()).src).toBe('provider')
  const file = join(dir, 'library.json')
  writeFileSync(file, JSON.stringify({ src: 'tmdb' }))
  const fresh = await servedLibrary(provider, provider, () => 0, file)
  expect(JSON.parse(Buffer.from(fresh.body).toString()).src).toBe('tmdb')
  expect(fresh.etag).not.toBe(missing.etag)
  expect((await servedLibrary(provider, provider, () => 0, file)).etag).toBe(fresh.etag)
})

test('the nightly rebuild leads with TrueScore\'s rows in its store\'s order (the classics without the recent ones), looks up their new films, and the rescore of its build splices a new one and the newest Letterboxd lists the same way', async () => {
  const film = (id: number, lang = 'en', genres = [35]): TmdbItem => ({ id, title: `F${id}`, release_date: '2020-01-01', original_language: lang, poster_path: `/${id}.jpg`, genre_ids: genres, popularity: 100 - id })
  const tmdb = async <T>(path: string) => (path.startsWith('genre/') ? { genres: [{ id: 35, name: 'Comedy' }] }
    : { results: path.startsWith('trending/movie/week?page=1') ? [film(1), film(2, 'ar')] : [] }) as T
  // TMDB's details, as the disk cache keeps them: 3 and 6 aren't in any list, 4 has no poster.
  const card = async (id: string) => ({ 'movie:3': fromTmdb('movie', film(3, 'ko')), 'movie:4': { id: 'movie:4', k: 'm' as const, n: 'F4' }, 'movie:5': fromTmdb('movie', film(5, 'ar')), 'movie:6': fromTmdb('movie', film(6)) })[id] ?? null
  const store = new Map([['movie:1', { score: 80 }], ['movie:3', { adjusted: 90, score: 70 }], ['movie:4', { adjusted: 70 }]])
  const asked: string[][] = []
  const deps = {
    tmdb, card, prev: null, seen: new Set(['movie:3']), store: () => ({ films: store }), topShows: [], dubbed: ['movie:5'],
    provider: [{ t: { id: 'movie:2', k: 'm' as const, n: 'F2', t: 'Reference' as const, e: 1 as const }, group: '4K' }],
    copies: async (lib: Library) => lib,
    score: async (ids: string[]) => (asked.push(ids), scoreTitles({}, store)),
    runtime: async (id: string) => (id === 'movie:1' ? 120 : undefined),
    today: new Date('2026-11-05'),
  }
  const { full, shown } = await rebuildLibrary(deps)
  const row = (lib: Library, id: string) => lib.rows.find((r) => r.id === id)?.i.map((i) => lib.titles[i].id)
  expect(row(shown, 'ts-recent')).toEqual(['movie:3']) // by adjusted score; the watched 3 kept for the app to hide; 4 has no poster
  expect(row(shown, 'ts-all')).toEqual(['movie:1']) // by all-time score, 3 being in the recent row
  expect(row(shown, 'ar-dub')).toBeUndefined() // 1 film, under DUB_MIN: the row waits
  expect(row(full, 'ar-dub')).toEqual(['movie:5'])
  expect(row(shown, 'trending')).toEqual(['movie:1', 'movie:2'])
  // A looked-up TrueScore film (3) and dubbed film (5) join their genre's row; the unscored sit at the row's median score (90).
  expect(row(shown, 'comedy')).toEqual(['movie:3', 'movie:2', 'movie:5', 'movie:1'])
  expect(shown.titles.find((t) => t.id === 'movie:1')).toMatchObject({ s: 80, rt: 120 })
  expect(shown.titles.find((t) => t.id === 'movie:2')).toMatchObject({ t: 'Reference', e: 1 })
  expect(asked[0]).toEqual(['movie:1', 'movie:2'])
  // TrueScore's store changes between builds: the server splices the new film in as the build would.
  store.set('movie:6', { adjusted: 95, score: 60 })
  // A Letterboxd list too: films TMDB has no poster for left out, the rest by score (the unscored at its median, 1's), after the TrueScore shows row.
  const list = { id: 'lb-u-x', n: 'X', at: 1, ids: ['movie:5', 'movie:2', 'movie:1', ...[20, 21, 22, 23, 24, 25, 26].map((n) => `movie:${n}`)] }
  const rescored = await showLibrary(full, { ...deps, films: store, lists: [list], scores: scoreTitles({}, store) })
  expect(row(rescored, 'ts-recent')).toEqual(['movie:6', 'movie:3'])
  expect(row(rescored, 'ts-all')).toEqual(['movie:1'])
  expect(rescored.rows.map((r) => r.id).slice(0, 3)).toEqual(['ts-recent', 'ts-all', 'lb-u-x'])
  expect(row(rescored, 'lb-u-x')).toEqual(['movie:5', 'movie:2', 'movie:1'])
  expect(row(rescored, 'comedy')).toEqual(row(shown, 'comedy'))
})
