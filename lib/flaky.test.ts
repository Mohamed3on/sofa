import { expect, test } from 'bun:test'
import { flakyFeeds } from './flaky.js'

const log = (...events: object[]) => events.map((e) => JSON.stringify(e)).join('\n')
const now = Date.parse('2026-11-08T22:00:00Z')
// The app left feed 1 for its sibling 2, which then played on.
const leftFor2 = (day: string) => ({ at: `${day}T20:00:00Z`, type: 'failover', from: 1, to: 2, playing: true })

test('a feed left for a sibling that held up, on two different days, is flaky', () => {
  expect(flakyFeeds(log(leftFor2('2026-11-07'), leftFor2('2026-11-08')), now)).toEqual(new Set([1]))
  expect(flakyFeeds(log(leftFor2('2026-11-08'), leftFor2('2026-11-08')), now)).toEqual(new Set())
  expect(flakyFeeds(log(leftFor2('2026-10-20'), leftFor2('2026-11-08')), now)).toEqual(new Set())
})

test('not when the sibling had trouble too, or the line did: that is not the feed', () => {
  const day = (d: string, trouble: object) => [leftFor2(d), { at: `${d}T20:03:00Z`, ...trouble }]
  for (const trouble of [{ type: 'failover', from: 2, to: 1 }, { type: 'rebuffer', id: 2 }, { type: 'stall', id: 2 }, { type: 'problem', kind: 'offline', id: 2 }])
    expect(flakyFeeds(log(...day('2026-11-07', trouble), ...day('2026-11-08', trouble)), now)).toEqual(new Set())
  // Trouble on the sibling after five minutes is another matter.
  const late = (d: string) => [leftFor2(d), { at: `${d}T20:06:00Z`, type: 'stall', id: 2 }]
  expect(flakyFeeds(log(...late('2026-11-07'), ...late('2026-11-08')), now)).toEqual(new Set([1]))
})
