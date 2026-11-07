import { expect, test } from 'bun:test'
import { personTitles, searchTitles } from './browse'
import type { Library } from './library'
import type { Known } from './titles'

const lib: Library = {
  v: 1, src: 'tmdb', at: '', img: '', gn: { '35': 'Comedy' },
  titles: [{ id: 'movie:1', k: 'm', n: 'Wadjda', l: 'ar' }, { id: 'tv:2', k: 't', n: 'Paranormal', l: 'ar' }, { id: 'movie:3', k: 'm', n: 'Heat', l: 'en' }],
  rows: [{ id: 'en', n: 'English', i: [2] }, { id: 'ar', n: 'Arabic', i: [0, 1] }],
}
const known = (scores: Known['scores'] = new Map()): Known => ({ lib, titles: new Map(lib.titles.map((t) => [t.id, t])), scores })

test('a title search puts TMDB\'s match first, the provider\'s copies after it without repeats', async () => {
  const tmdb = async <T>(path: string) => {
    expect(path).toStartWith('search/multi?query=inception')
    return { results: [
      { id: 27205, media_type: 'movie', title: 'Inception', release_date: '2010-07-15', poster_path: '/i.jpg' },
      { id: 9, media_type: 'person', name: 'Someone' },
      { id: 64956, media_type: 'movie', title: 'Inception: The Cobol Job', poster_path: '/c.jpg' },
    ] } as T
  }
  const vod = async () => [
    { id: 5, titleId: 'movie:27205', name: 'EN - Inception (2010)', poster: '', group: 'g', ext: 'mkv' },
    { id: 6, titleId: 'iptv:6', name: 'EN - Inception Making Of', poster: '', group: 'g', ext: 'mkv' },
  ]
  const ids = (await searchTitles('inception', { known: known(new Map([['movie:27205', { s: 900, sn: 80 }]])), tmdb, vod })).map((t) => t.id)
  expect(ids).toEqual(['movie:27205', 'movie:64956', 'iptv:6'])
})

test('a person\'s credits come best score first, then the most voted, directing counted once', () => {
  const credits = {
    cast: [
      { id: 1, media_type: 'movie', title: 'Low', poster_path: '/1.jpg', vote_count: 9000 },
      { id: 2, media_type: 'movie', title: 'High', poster_path: '/2.jpg', vote_count: 10 },
      { id: 3, media_type: 'tv', name: 'Talk', poster_path: '/3.jpg', genre_ids: [10767], vote_count: 99999 },
      { id: 4, media_type: 'movie', title: 'Unscored', poster_path: '/4.jpg', vote_count: 500 },
    ],
    crew: [{ id: 2, media_type: 'movie', title: 'High', poster_path: '/2.jpg', job: 'Director' }],
  }
  const scores = new Map([['movie:1', { s: 100 }], ['movie:2', { s: 700, sn: 70 }]])
  expect(personTitles(credits, known(scores)).map((t) => [t.id, t.s])).toEqual([['movie:2', 700], ['movie:1', 100], ['movie:4', undefined]])
})
