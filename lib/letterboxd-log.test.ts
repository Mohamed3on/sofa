import { expect, test } from 'bun:test'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

process.env.LETTERBOXD_LOG_FILE = join(mkdtempSync(join(tmpdir(), 'lblog-')), 'log.json')
const { logged, noteMark, pendingLogs } = await import('./letterboxd-log.js')

test('the TV\'s changes fold into one entry per film, and undoing deletes only what the app made', async () => {
  await noteMark('tv:1396', { watched: true, at: 1 }) // a show: Letterboxd here is films
  await noteMark('movie:1', { watched: true, at: 10 })
  await noteMark('movie:1', { rating: 8 })
  await noteMark('movie:2', { watched: true, at: 20 })
  await noteMark('movie:2', { watched: false }) // unwatched before it went: nothing to do
  await noteMark('movie:3', { rating: 6, at: 30 }) // rated only: the rating, no diary entry
  await noteMark('movie:4', { watchlist: false, at: 40 })
  expect(pendingLogs()).toEqual([
    { tmdb: 1, at: 10, log: true, rating: 8 },
    { tmdb: 3, at: 30, rating: 6 },
    { tmdb: 4, at: 40, watchlist: false },
  ])

  // The browser logs film 1 (Letterboxd calls the entry "e1") while it's re-rated: the new rating stays to send.
  const sent = pendingLogs()
  await noteMark('movie:1', { rating: 9 })
  await logged([{ ...sent[0], made: 'e1' }, sent[1], sent[2]])
  expect(pendingLogs()).toEqual([{ tmdb: 1, at: 10, rating: 9 }])

  // Unwatched: the app's entry goes, the rating is cleared as asked; watched again before that went, the entry stays.
  await noteMark('movie:1', { watched: false })
  await noteMark('movie:1', { rating: null })
  expect(pendingLogs()).toEqual([{ tmdb: 1, at: 10, rating: null, unlog: ['e1'] }])
  await noteMark('movie:1', { watched: true, at: 50 })
  expect(pendingLogs()).toEqual([{ tmdb: 1, at: 10, rating: null }])
  await noteMark('movie:1', { watched: false })
  await logged(pendingLogs())
  expect(pendingLogs()).toEqual([])
  await noteMark('movie:1', { watched: false }) // nothing of the app's left to delete
  expect(pendingLogs()).toEqual([])
})
