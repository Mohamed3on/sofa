import { expect, test } from 'bun:test'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { exchangeState } from './app-state'

test('each key keeps its newest write, removals included', async () => {
  process.env.APP_STATE_FILE = join(mkdtempSync(join(tmpdir(), 'state-')), 'state.json')
  await exchangeState({ fav: { v: 'a', at: 10 }, recent: { v: 'x', at: 10 } })
  // An older write (a device that was offline) loses; a newer removal wins.
  const kept = await exchangeState({ fav: { v: 'stale', at: 5 }, recent: { v: null, at: 20 } })
  expect(kept).toEqual({ fav: { v: 'a', at: 10 }, recent: { v: null, at: 20 } })
})
