import { expect, test } from 'bun:test'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { assemble, rowDefs, servedLibrary, type Library } from './_library'
import { fromTmdb } from './_titles'

test('films land in their language rows (ko under Other), provider tiers join TMDB titles, a title with no copy has none', () => {
  const defs = rowDefs(new Date('2026-11-05'))
  const film = (id: number, lang: string) => fromTmdb('movie', { id, title: `F${id}`, original_language: lang, poster_path: `/${id}.jpg`, genre_ids: [35] })
  const seeds = defs.map((d) => (d.id === 'trending' ? [film(1, 'en'), film(2, 'ar'), film(3, 'ru'), film(4, 'ko')] : []))
  const lib = assemble(defs, seeds, [{ t: { id: 'movie:1', k: 'm', n: 'F1', t: 'Reference', e: 1 }, group: '🎬 NETFLIX MOVIES 4K' }], null, {})
  const row = (id: string) => lib.rows.find((r) => r.id === id)!.i.map((i) => lib.titles[i].id)
  expect(row('en')).toEqual(['movie:1'])
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
