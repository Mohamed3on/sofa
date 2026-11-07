import { expect, test } from 'bun:test'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

process.env.LETTERBOXD_LOG_FILE = join(mkdtempSync(join(tmpdir(), 'lblog-')), 'log.json')
const { logged, noteMark, pendingLogs } = await import('./letterboxd-log.js')

test('marks fold into one log per film, and only what the browser sent leaves', async () => {
  await noteMark('tv:1396', { watched: true, at: 1 }) // a show: Letterboxd logs films
  await noteMark('movie:1', { rating: 8, at: 10 }) // rated only: logged, dated now
  await noteMark('movie:1', { watched: true, at: 20 }) // then watched: the play's date, the rating kept
  await noteMark('movie:2', { watched: true, at: 30 })
  await noteMark('movie:2', { watched: false }) // unmarked before it went: never logged
  await noteMark('movie:3', { rating: null }) // a rating cleared with nothing waiting: nothing
  expect(pendingLogs()).toEqual([{ tmdb: 1, at: 20, rating: 8 }])

  const sent = pendingLogs()
  await noteMark('movie:1', { rating: 9 }) // re-rated while the browser was logging the 8
  await logged(sent)
  expect(pendingLogs()).toEqual([{ tmdb: 1, at: 20, rating: 9 }])
  await logged(pendingLogs())
  expect(pendingLogs()).toEqual([])
})
