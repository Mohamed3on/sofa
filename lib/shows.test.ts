import { expect, test } from 'bun:test'
import { buildShowList } from './shows'

test('a show is kept once per language, the 4K copy first, English rows leading', () => {
  const cats = [
    { id: '1', name: 'FIFA+ SERIES' },
    { id: '2', name: 'APPLE+ SERIES ⁴ᴷ ³⁸⁴⁰ᴾ ᴰᵒˡᵇʸ ⱽᶦˢᶦᵒⁿ' },
    { id: '3', name: 'APPLE+ SERIES' },
    { id: '4', name: 'GERMANY APPLE ⁴ᴷ ³⁸⁴⁰ᴾ' },
    { id: '5', name: 'FRANCE SÉRIES' },
    { id: '6', name: 'NETFLIX PODCAST' },
  ]
  const show = (id: number, cat: string, name: string, tmdb = '95396') => ({ id, name, cat, tmdb, cover: '' })
  const list = buildShowList({ cats, series: [
    show(1, '3', 'A+ - Severance (2022) (US)'),
    show(2, '2', '4K-A+ - Severance (2022) (US)'),
    show(3, '4', '4K-DE - Severance (2022) (US)'),
    show(4, '5', 'FR - Severance (2022)'),
    show(5, '1', 'EN - World Cup Diaries', ''),
    show(6, '6', 'EN - Some Podcast', ''),
  ] })
  expect(list.movies.map((m) => [m.id, m.titleId, m.name, m.group])).toEqual([
    [2, 'tv:95396', 'Severance (2022) (US)', 'APPLE+ SERIES'],
    [5, 'iptv:tv:5', 'World Cup Diaries', 'FIFA+ SERIES'],
    [3, 'tv:95396', 'Severance (2022) (US)', 'GERMANY APPLE'],
  ])
})
