import { afterAll, beforeAll, describe, expect, mock, spyOn, test } from 'bun:test'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { vodChannels } from './curation.js'
import { claims4K, readName, seriesCatalogue, vodCatalogue } from './provider.js'
import { bestFilmCopies, searchVod } from './vod.js'

describe('provider names', () => {
  test.each([
    // [provider name, category, title, language tag, dubbed, subbed]
    ['EN - Inception (2010)', 'EN - 2020 & OLD', 'Inception (2010)', 'EN', undefined, undefined],
    ['4K-ES-HDR - Scary Movie (2026)', 'ES - PELÍCULAS', 'Scary Movie (2026)', 'ES', 'es', undefined],
    ['AR-SUBS - Love in 39 Degrees (2024)', '', 'Love in 39 Degrees (2024)', 'AR', undefined, 'ar'],
  ['AR-DUB - Finding Nemo (2003) البحث عن نيمو', '', 'Finding Nemo (2003) البحث عن نيمو', 'AR', 'ar', undefined],
  ['IN-EN - The Odyssey (2026)', '', 'The Odyssey (2026)', 'EN', undefined, undefined],
    ['4K-D+  -Pita Hall (2025)', 'DISNEY+ ⁴ᴷ', 'Pita Hall (2025)', '', undefined, undefined],
    ['4M-AMZ - The Neverending Wedding (2025)', '', 'The Neverending Wedding (2025)', '', undefined, undefined],
    ['4K-DE - Severance (2022) (US)', 'GERMANY APPLE ⁴ᴷ ³⁸⁴⁰ᴾ', 'Severance (2022) (US)', 'DE', 'de', undefined],
    ['TOP - Heat (1995)', 'EN - NEW RELEASE', 'Heat (1995)', 'EN', undefined, undefined],
    ['Something', 'PT/BR - FILMES 2025-2026', 'Something', 'PT', 'pt', undefined],
    ['WALL-E (2008)', '', 'WALL-E (2008)', '', undefined, undefined],
    ['110. Amélie', '', 'Amélie', '', undefined, undefined],
    ['Eternal.Sunshine.of.the.Spotless.Mind.2004', '', 'Eternal Sunshine of the Spotless Mind (2004)', '', undefined, undefined],
  ])('%s', (name, category, title, lang, dubbed, subbed) => {
    expect(readName(name, category)).toEqual({ title, lang, ...(dubbed && { dubbed }), ...(subbed && { subbed }) })
  })

  test('4K claims, superscript spellings included', () => {
    for (const c of ['NETFLIX MOVIES ⁴ᴷ ³⁸⁴⁰ᴾ ᴰᵒˡᵇʸ ⱽᶦˢᶦᵒⁿ', 'DE - ²¹⁶⁰ᵖ DV ᴴᴰᴿ¹⁰/PLUS', 'IT - 4K MOVIES', 'EN - MOVIES UHD', 'أفلام فائقة الوضوح']) expect(claims4K(c)).toBe(true)
    for (const c of ['TOP MOVIES BLURAY (MULTI-SUBS)', 'EN - KIDS', 'NETFLIX MOVIES']) expect(claims4K(c)).toBe(false)
  })
})

describe('catalogue snapshots', () => {
  const cfg = { host: 'http://provider.test', user: 'u', pass: 'p' }
  const dir = mkdtempSync(`${tmpdir()}/sofa-snapshots-`)
  const env = { ...process.env }
  const calls: string[] = []
  let answer: (action: string) => unknown = () => []
  const vod = [
    { stream_id: 1, name: 'EN - Inception (2010)', category_id: '10', container_extension: 'mkv', added: '100', tmdb: '27205' },
    { stream_id: 2, name: '4K-EN - Inception (2010)', category_id: '20', container_extension: 'mkv', added: '200', tmdb: '27205' },
    { stream_id: 3, name: 'FR - Inception (2010)', category_id: '30', container_extension: 'mp4', added: '300', tmdb: '27205' },
    { stream_id: 4, name: 'EN - Adult', category_id: '10', is_adult: '1' },
    { stream_id: 5, name: 'EN - UFC 300: Pereira vs Hill', category_id: '10', container_extension: 'mp4', added: '400' },
  ]
  const vodCats = [{ category_id: '10', category_name: 'EN - MOVIES' }, { category_id: '20', category_name: 'EN - MOVIES ⁴ᴷ ³⁸⁴⁰ᴾ' }, { category_id: '30', category_name: 'FR - FILMS' }]
  beforeAll(() => {
    process.env.SNAPSHOT_DIR = dir
    spyOn(globalThis, 'fetch').mockImplementation((async (url: string) => {
      const action = new URL(url).searchParams.get('action') ?? ''
      calls.push(action)
      return Response.json(answer(action))
    }) as typeof fetch)
  })
  afterAll(() => {
    process.env = { ...env }
    rmSync(dir, { recursive: true, force: true })
    mock.restore() // the fetch stub, which would otherwise answer the test files after this one
  })

  test('the VOD catalogue is pulled once, then every reader and playlist build uses the snapshot', async () => {
    answer = (a) => (a === 'get_vod_streams' ? vod : a === 'get_vod_categories' ? vodCats : [])
    const first = await vodCatalogue(cfg)
    expect(first.films.map((f) => f.id)).toEqual([1, 2, 3, 5]) // adult titles left out
    expect(first.films[0]).toEqual({ id: 1, name: 'EN - Inception (2010)', icon: '', cat: 10, ext: 'mkv', added: 100, tmdb: '27205' })
    await vodCatalogue(cfg)
    expect((await vodChannels(cfg)).map((c) => [c.name, c.group])).toEqual([['EN - UFC 300: Pereira vs Hill', '🎬 UFC PPV Replays (VOD)'], ['Inception (2010)', '🎬 EN - MOVIES 4K']])
    expect(calls.filter((a) => a === 'get_vod_streams')).toHaveLength(1)
  })

  test('VOD search and best copy read the snapshot: 4K first, other languages last', async () => {
    expect((await searchVod(cfg, 'incep')).map((m) => [m.id, m.name])).toEqual([[2, 'EN - Inception (2010)']])
    expect((await bestFilmCopies(cfg)).get('movie:27205')).toEqual([2, 'mkv'])
  })

  test('a stale snapshot is served at once; an empty pull keeps it and is not retried for an hour', async () => {
    const saved = { cats: [{ id: '1', name: 'NETFLIX SERIES' }], series: [{ id: 9, name: 'EN - Dark', cover: '', cat: '1', tmdb: '' }] }
    await Bun.write(`${dir}/series.json`, JSON.stringify({ at: Date.now() - 2 * 864e5, data: saved }))
    answer = () => []
    expect(await seriesCatalogue(cfg)).toEqual(saved)
    await Bun.sleep(20) // the background pull
    expect(calls.filter((a) => a === 'get_series')).toHaveLength(1)
    expect((await Bun.file(`${dir}/series.json`).json()).data).toEqual(saved)
    expect(await seriesCatalogue(cfg)).toEqual(saved)
    expect(calls.filter((a) => a === 'get_series')).toHaveLength(1)
  })
})
