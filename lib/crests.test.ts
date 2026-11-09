import { expect, test } from 'bun:test'
import { crestKey, fixtureTeams, pickCrest } from './crests.js'

// Titles as the guide carries them (2026-11-08), each a broadcaster's own way of naming one match.
test('a match title gives its two teams, home first, whatever the broadcaster wraps round them', () => {
  const teams = (title: string) => fixtureTeams(title)?.map(crestKey)
  expect(teams('Live PL: Man United v Aston Villa')).toEqual(['manchester united', 'aston villa'])
  expect(teams('Premier League - Jornada 10: Manchester Utd. - Aston Villa')).toEqual(['manchester united', 'aston villa'])
  expect(teams('Live PL: Manchester United - Aston Villa, Match of the Week, 10. Spieltag ᴸᶦᵛᵉ')).toEqual(['manchester united', 'aston villa'])
  expect(teams('PL - Manchester United - Aston Villa ᴸᶦᵛᵉ')).toEqual(['manchester united', 'aston villa'])
  expect(teams('Crystal Palace vs Liverpool (MW10) - Premier League 2026/27 - Premier League 2026/27')).toEqual(['crystal palace', 'liverpool'])
  expect(teams('Live PL: C. Palace v Liverpool')).toEqual(['crystal palace', 'liverpool'])
  expect(teams('LALIGA EA SPORTS 2026/2027 MD12 : Atlético de Madrid vs FC Barcelona ᴸᶦᵛᵉ')).toEqual(['atletico madrid', 'barcelona'])
  expect(teams('Serie A:Inter x Como')).toEqual(['inter milan', 'como'])
  expect(teams('Live Serie A Fiorentina v Juventus')).toEqual(['fiorentina', 'juventus'])
  expect(teams('Live: Bundesliga - SC Freiburg - 1. FC Union Berlin ᴸᶦᵛᵉ')).toEqual(['freiburg', 'union berlin'])
  expect(teams('Live PL: Ipswich Town - AFC Bournemouth, 10. Spieltag - Ipswich Town - Bournemouth')).toEqual(['ipswich town', 'bournemouth'])
  expect(teams('UEFA Champions League -- Atlético Madrid vs Bayern')).toEqual(['atletico madrid', 'bayern munich'])
  expect(teams('Bundesliga Clubs - SC Freiburg')).toBeUndefined()
  expect(teams('PREMIER LEAGUE LIVE')).toBeUndefined()
})

test('a crest is taken only from a football team of that name, never a near miss', () => {
  const team = (strTeam: string, strBadge: string, more: object = {}) => ({ strTeam, strBadge, strSport: 'Soccer', strGender: 'Male', ...more })
  expect(pickCrest('manchester united', [team('Manchester United', 'mu.png')])).toBe('mu.png')
  // TheSportsDB's answer to "Man United" (why the short names are expanded first).
  expect(pickCrest(crestKey('Man United'), [team('Manly United', 'manly.png')])).toBeNull()
  expect(pickCrest('atletico madrid', [team('Atlético Madrid', 'atm.png')])).toBe('atm.png')
  expect(pickCrest('grimsby', [team('Grimsby Town', 'gt.png')])).toBe('gt.png')
  expect(pickCrest('arsenal', [team('Arsenal', 'w.png', { strGender: 'Female' }), team('Arsenal', 'a.png')])).toBe('a.png')
  expect(pickCrest('inter', [team('Intercity', 'ic.png')])).toBeNull()
})
