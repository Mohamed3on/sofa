import { describe, expect, test } from 'bun:test'
import { matchState, type EspnEvent } from './live-matches.js'

const now = Date.parse('2026-11-08T17:00:00Z')
const event = (state: string, extra: Partial<EspnEvent['status']['type']> = {}, clock = "67'", date = '2026-11-08T16:30Z'): EspnEvent => ({
  date,
  status: { displayClock: clock, type: { state, ...extra } },
  competitions: [{ competitors: [
    { homeAway: 'away', score: '4', team: { displayName: 'Aston Villa', shortDisplayName: 'Aston Villa' } },
    { homeAway: 'home', score: '3', team: { displayName: 'Manchester United', shortDisplayName: 'Man Utd' } },
  ] }],
})

describe('matchState', () => {
  test('files a match under home|away keys, as the guide names its teams', () => {
    expect(matchState(event('in'), now)).toEqual(['manchester united|aston villa', { hs: 3, as: 4, state: 'in', clock: "67'" }])
  })
  test('half time, full time and kick-off in Berlin time', () => {
    expect(matchState(event('in', { name: 'STATUS_HALFTIME' }, "45'+3'"), now)?.[1].clock).toBe('HT')
    expect(matchState(event('post', { completed: true, shortDetail: 'FT' }, "90'+6'"), now)?.[1].clock).toBe('FT')
    expect(matchState(event('pre', {}, "0'", '2026-11-08T20:00Z'), now)?.[1]).toEqual({ hs: 3, as: 4, state: 'pre', clock: '21:00' })
  })
  test('postponed matches and other days are left out', () => {
    expect(matchState(event('post', { completed: false, shortDetail: 'Postponed' }), now)).toBeNull()
    expect(matchState(event('pre', {}, "0'", '2026-11-11T20:00Z'), now)).toBeNull()
  })
})
