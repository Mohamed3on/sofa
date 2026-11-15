import { expect, test } from 'bun:test'
import { airsLive, fixtureVerdict, slotFixture, type Fixture, type Fixtures } from './fixtures.js'

// Tonight's (2026-11-15) Nations League evening and the re-runs that followed it, as the guide had them.
const H = 3600
const at = (iso: string) => Date.parse(iso) / 1000
const side = (key: string) => ({ key, crest: `${key}.png`, name: key })
const fixture = (id: string, comp: string, kickoff: string, home: string, away: string): Fixture => ({ id, comp, kickoff: at(kickoff), home: side(home), away: side(away) })
const spainEngland = fixture('1', 'unl', '2026-11-15T19:45:00Z', 'spain', 'england')
const franceTurkey = fixture('2', 'unl', '2026-11-15T19:45:00Z', 'france', 'turkiye')
const barcaGetafe = fixture('3', 'laliga', '2026-11-16T15:00:00Z', 'barcelona', 'getafe')
const all = [spainEngland, franceTurkey, barcaGetafe]
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

test('an event slot\'s name is its fixture when it names two teams that play; last weekend\'s is stale; others say nothing', () => {
  const leagues: Record<string, string[]> = { spain: ['uefa.nations'], england: ['uefa.nations'], 'manchester united': ['eng.1', 'eng.fa'], 'crystal palace': ['eng.1'], ajax: ['ned.1', 'uefa.champions'], psv: ['ned.1'] }
  const KEYS2: Record<string, string> = { ...KEYS, spanien: 'spain', barcelona: 'barcelona', alaves: 'alaves', 'manchester united': 'manchester united', 'crystal palace': 'crystal palace', ajax: 'ajax', psv: 'psv' }
  const slot = (name: string) => slotFixture(fx(), name, at('2026-11-15T20:30:00Z'), (t) => KEYS2[t.toLowerCase()] ?? null, (t) => leagues[KEYS2[t.toLowerCase()]] ?? null)
  expect(slot('UEFA | 08 - Spain vs England 7:45pm')).toBe(spainEngland)
  expect(slot('Live | Spanien - England | UEFA Nations League | 8K EXCLUSIVE | DE: DAZN PPV 14')).toBe(spainEngland)
  expect(slot('Live Football 09 : Manchester United vs Crystal Palace 12:45 pm')).toBe('stale')
  // Ajax plays in a league we don't read: its match may well be on.
  expect(slot('Live | Ajax vs. PSV | all | 8K EXCLUSIVE | DE: SOCCER PPV 16')).toBeUndefined()
  // A club's channel: its club's match nearest; none then and it's stale.
  expect(slot('UK: LA LIGA - BARCELONA')).toBe(barcaGetafe)
  expect(slot('UK: LA LIGA - ALAVES')).toBe('stale')
  expect(slot('UK: LA LIGA - VILLAREAL')).toBeUndefined() // a club name we can't read leaves the channel as it was
  expect(slot('IT: PRIM 1 4K [LIVE-EVENT]')).toBeUndefined()
})

test('a slot\'s name with unknown teams or no fixture: stale only when no match of ours can be on', () => {
  const leagues: Record<string, string[]> = { france: ['uefa.nations'], lille: ['fra.1', 'uefa.europa.conf'] }
  const KEYS3: Record<string, string> = { ...KEYS, lille: 'lille' }
  const slot = (name: string, now = '2026-11-15T20:30:00Z') => slotFixture(fx(), name, at(now), (t) => KEYS3[t.toLowerCase()] ?? null, (t) => leagues[KEYS3[t.toLowerCase()]] ?? null)
  // Antigua's Premier League names ours, but ESPN lists every team of a league we read.
  expect(slot('Live | Potters Tigers FC vs. Green City FC | Antigua Premier League | 8K EXCLUSIVE | DE: DAZN PPV 9')).toBe('stale')
  // Neither team known and no competition of ours named: another league's match, maybe live.
  expect(slot('Live | Ureña SC vs. Barquisimeto SC | Segunda División - Venezuela | 8K EXCLUSIVE | ES: DAZN PPV 10')).toBeUndefined()
  // One known team without a fixture then, playing only in leagues we read.
  expect(slot('UEFA | 26 - Lille v KÍ Klaksvík 20.00')).toBe('stale')
  // A show named for La Liga: stale while La Liga has no fixture near, left alone when it has.
  expect(slot('Live | La Dazoneta 24 Horas | Parte 1 | LALIGA EA SPORTS | 8K EXCLUSIVE | ES: DAZN PPV 1', '2026-11-13T12:00:00Z')).toBe('stale')
  expect(slot('Live | La Dazoneta 24 Horas | Parte 1 | LALIGA EA SPORTS | 8K EXCLUSIVE | ES: DAZN PPV 1')).toBeUndefined()
})
