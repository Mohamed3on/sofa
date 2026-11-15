import { expect, test } from 'bun:test'
import { airsLive, fixtureVerdict, type Fixture, type Fixtures } from './fixtures.js'

// Tonight's (2026-11-15) Nations League evening and the re-runs that followed it, as the guide had them.
const H = 3600
const at = (iso: string) => Date.parse(iso) / 1000
const side = (key: string) => ({ key, crest: `${key}.png`, name: key })
const fixture = (id: string, comp: string, kickoff: string, home: string, away: string): Fixture => ({ id, comp, kickoff: at(kickoff), home: side(home), away: side(away) })
const spainEngland = fixture('1', 'unl', '2026-11-15T19:45:00Z', 'spain', 'england')
const franceTurkey = fixture('2', 'unl', '2026-11-15T19:45:00Z', 'france', 'turkiye')
const all = [spainEngland, franceTurkey]
const fx = (covered = true): Fixtures => ({
  near: (from, to, comp) => all.filter((f) => f.kickoff >= from && f.kickoff <= to && (!comp || f.comp === comp)),
  covered: () => covered,
})
// What ESPN's lists and Intl make of the team names (lib/crests.ts espnTeamKey).
const KEYS: Record<string, string> = { spagna: 'spain', inghilterra: 'england', francia: 'france', turchia: 'turkiye', spain: 'spain', england: 'england', inter: 'inter milan', como: 'como' }
const key = (team: string) => KEYS[team.toLowerCase()] ?? null
const verdict = (title: string, start: string, hours: number, comp?: string, jev: Record<string, string> = {}) =>
  fixtureVerdict(fx(), title, at(start), at(start) + hours * H, comp, jev, key)

test('a programme airs a fixture live from its kick-off; later airings of it are re-runs', () => {
  expect(verdict('Spagna - Inghilterra', '2026-11-15T19:35:00Z', 3.4)).toMatchObject({ live: true, comp: 'unl', fixture: spainEngland })
  // A feed joining at half-time still shows it live; one starting after the final whistle doesn't.
  expect(verdict('Spain vs England - UEFA Nations League', '2026-11-15T20:40:00Z', 1, 'unl')).toMatchObject({ live: true })
  expect(verdict('Spain vs England - UEFA Nations League', '2026-11-15T22:30:00Z', 2, 'unl')).toEqual({ live: false, rerun: true })
  // A build-up show that ends at kick-off isn't the match.
  expect(airsLive(spainEngland, at('2026-11-15T18:30:00Z'), at('2026-11-15T19:45:00Z'))).toBe(false)
})

test('a title naming our competition with none of its fixtures on is a re-run; another league\'s match is left alone', () => {
  // Inter–Como was played days ago; at 23:00 a channel airs it again titled as live.
  expect(verdict('Serie A:Inter x Como', '2026-11-15T22:00:00Z', 2, 'seriea')).toEqual({ live: false, rerun: true })
  // Teams ESPN lists, no competition of ours named, no fixture: maybe live in a league we don't follow.
  expect(verdict('Inter x Como', '2026-11-15T22:00:00Z', 2)).toEqual({ live: false, rerun: false })
  expect(verdict('Gibraltar U19 vs Malta', '2026-11-15T19:45:00Z', 2, 'unl')).toEqual({ live: false, rerun: false })
})

test('what the fixtures can\'t tell stays with the guide\'s rules until Jev places it', () => {
  // ESPN not read for that day: no verdict.
  expect(fixtureVerdict(fx(false), 'Spagna - Inghilterra', at('2026-11-15T19:35:00Z'), at('2026-11-15T23:00:00Z'), undefined, {}, key)).toBeUndefined()
  // A whip-around names no teams: Jev's to place, among the fixtures near it.
  const konferenz = 'DAZN ONLY - UEFA Nations League 26/27 : Konferenz ᴸᶦᵛᵉ'
  expect(verdict(konferenz, '2026-11-15T19:15:00Z', 2.5, 'unl')).toBeUndefined()
  const asked = JSON.stringify([konferenz, ['1', '2']])
  expect(verdict(konferenz, '2026-11-15T19:15:00Z', 2.5, 'unl', { [asked]: 'several' })).toEqual({ live: true, comp: 'unl' })
  expect(verdict(konferenz, '2026-11-15T19:15:00Z', 2.5, 'unl', { [asked]: 'none' })).toEqual({ live: false, rerun: true })
  expect(verdict(konferenz, '2026-11-15T19:15:00Z', 2.5, 'unl', { [asked]: 'unsure' })).toBeUndefined()
  // One team read, one fixture of it on: that fixture.
  expect(verdict('Francia - Türkiye U', '2026-11-15T19:30:00Z', 2.5)).toMatchObject({ live: true, fixture: franceTurkey })
})
