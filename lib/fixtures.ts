// Real fixtures, the ground truth Live now and the 🏆 groups go by: ESPN's keyless scoreboards for the competitions COMPS
// has, yesterday to tomorrow (UTC), read again hourly and kept on disk. A guide programme naming a fixture is a live match
// only when it airs that fixture from its kick-off ([fixtureVerdicts]); a title the code can't read is asked of Jev once,
// after the build that met it ([judgeFixtures]). A competition-day ESPN couldn't be read for counts as uncovered, and its
// programmes go by the guide's own rules as before.
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { espnKey, espnTeamKey, espnTeamLeagues, fixtureTeams, type EspnTeam, type TitleMatch } from './crests.js'
import { COMPS, YOUTH } from './curation.js'
import { askJev, type JevQuestion } from './jev.js'
import { writeAtomic, jsonFile } from './fs.js'
import { getJson } from './http.js'

/** ESPN's league → our COMPS key. */
export const LEAGUE_COMP: Record<string, string> = {
  'eng.1': 'pl', 'esp.1': 'laliga', 'ger.1': 'bundes', 'ita.1': 'seriea', 'fra.1': 'ligue1', 'uefa.champions': 'ucl', 'uefa.europa': 'uel',
  'uefa.europa.conf': 'uecl', 'eng.2': 'champ', 'esp.copa_del_rey': 'cdr', 'eng.fa': 'facup', 'eng.league_cup': 'eflcup', 'ita.coppa_italia': 'coppa',
  'ger.dfb_pokal': 'pokal', 'uefa.nations': 'unl', 'uefa.euroq': 'euro', 'uefa.euro': 'euro', 'fifa.worldq.uefa': 'wc', 'fifa.world': 'wc',
  'caf.nations_qual': 'afcon', 'caf.nations': 'afcon',
}

export interface Side { key: string; crest: string | null; name: string }
export interface Fixture { id: string; comp: string; kickoff: number; home: Side; away: Side }

interface Event {
  id: string; date: string; name?: string
  status: { type: { name?: string } }
  competitions: { competitors: { homeAway: string; team: EspnTeam & { logo?: string } }[] }[]
}

const DAY_S = 86_400
const REFRESH_MS = 3600_000
const DROPPED = new Set(['STATUS_POSTPONED', 'STATUS_CANCELED', 'STATUS_CANCELLED'])
const file = () => process.env.FIXTURES_FILE ?? fileURLToPath(new URL('../.cache/fixtures.json', import.meta.url))
const ymd = (s: number) => new Date(s * 1000).toISOString().slice(0, 10).replace(/-/g, '')

/** Each "league|YYYYMMDD" ESPN answered for: its fixtures and when. */
type Kept = Record<string, { at: number; fixtures: Fixture[] }>
let kept: Kept | null = null
let loading: Promise<void> | null = null
let lastRun = 0
/** Bumped whenever the kept fixtures change, so work cached per guide is redone. */
export let fixturesVersion = 0

function load(): Kept {
  if (!kept) try { kept = JSON.parse(readFileSync(file(), 'utf8')) as Kept } catch { kept = {} }
  return kept!
}

const side = (t: EspnTeam & { logo?: string }): Side => ({
  key: espnKey(t),
  crest: t.logo ? `https://a.espncdn.com/combiner/i?img=${new URL(t.logo).pathname}&w=64&h=64` : null,
  name: t.shortDisplayName || t.displayName || '',
})

/** A scoreboard's fixtures for [comp]: postponed and called-off ones left out. */
export function fixturesOf(events: Event[], comp: string): Fixture[] {
  return events.flatMap((e) => {
    const teams = e.competitions?.[0]?.competitors ?? []
    const home = teams.find((t) => t.homeAway === 'home')
    const away = teams.find((t) => t.homeAway === 'away')
    if (!home || !away || DROPPED.has(e.status?.type?.name ?? '')) return []
    return [{ id: e.id, comp, kickoff: Date.parse(e.date) / 1000, home: side(home.team), away: side(away.team) }]
  })
}

async function refresh() {
  const k = load()
  const today = Math.floor(Date.now() / 1000 / DAY_S) * DAY_S
  const days = [today - DAY_S, today, today + DAY_S].map(ymd)
  const jobs = Object.entries(LEAGUE_COMP).flatMap(([league, comp]) => days.map((d) => ({ league, comp, d })))
  let changed = false
  for (let i = 0; i < jobs.length; i += 6) {
    await Promise.all(jobs.slice(i, i + 6).map(async ({ league, comp, d }) => {
      try {
        const res = await getJson<{ events?: Event[] }>(`https://site.api.espn.com/apis/site/v2/sports/soccer/${league}/scoreboard?dates=${d}`, { timeoutMs: 10_000, retries: 1 })
        k[`${league}|${d}`] = { at: Date.now(), fixtures: fixturesOf(res.events ?? [], comp) }
        changed = true
      } catch (e) {
        console.warn(`fixtures: ESPN ${league} ${d}: ${e instanceof Error ? e.message : e}`)
      }
    }))
  }
  // Days gone from the window are dropped.
  for (const key of Object.keys(k)) if (!days.includes(key.split('|')[1])) { delete k[key]; changed = true }
  if (changed) {
    fixturesVersion++
    await writeAtomic(file(), k)
  }
}

/** Starts a refresh when the kept fixtures are an hour old; never waits. */
function maybeRefresh() {
  if (Date.now() - lastRun < REFRESH_MS || loading || process.env.NODE_ENV === 'test') return
  lastRun = Date.now()
  loading = refresh().finally(() => (loading = null))
}

/** Before a guide build: a first start (nothing kept yet) waits for ESPN, a few seconds, so that build already has fixtures. */
export async function fixturesReady() {
  const k = load()
  maybeRefresh()
  if (!Object.keys(k).length) await loading
}

/** The fixtures as kept, and which competition-days ESPN answered for within the last day. */
export interface Fixtures {
  /** Fixtures of [comp] (any when undefined) kicking off from [from] to [to], seconds. */
  near(from: number, to: number, comp?: string): Fixture[]
  /** Whether every league of [comp] (every league, when undefined) was read for the time [t] falls in. */
  covered(t: number, comp?: string): boolean
}

export function keptFixtures(k: Kept = load()): Fixtures {
  maybeRefresh()
  const all = Object.values(k).flatMap((v) => v.fixtures)
  const fresh = new Set(Object.entries(k).filter(([, v]) => Date.now() - v.at < DAY_S * 1000).map(([key]) => key))
  // ESPN files a match under its US date, so an instant's UTC day and the day before must both have been read.
  const read = (league: string, t: number) => [t, t - DAY_S].every((s) => fresh.has(`${league}|${ymd(s)}`))
  return {
    near: (from, to, comp) => all.filter((f) => f.kickoff >= from && f.kickoff <= to && (!comp || f.comp === comp)),
    covered: (t, comp) => Object.entries(LEAGUE_COMP).every(([league, c]) => (comp && c !== comp) || read(league, t)),
  }
}

// --- Which fixture a programme airs -----------------------------------------------------------------------

/** How long a match runs, extra time and the break in: a programme airs it live when it covers enough of this from kick-off. */
const MATCH_S = 115 * 60
/** A programme starting later than this after kick-off is a re-run (a feed joining a match late still counts). */
const LATE_S = 60 * 60
/** Kick-offs looked at for a programme: from 3 h before it starts, so a programme mid-match still finds its fixture. */
const BEFORE_S = 3 * 3600

/** Whether a programme from [start] to [stop] (s) shows [f] live: it covers half an hour of the match (or half of itself), starting by an hour in. */
export function airsLive(f: Fixture, start: number, stop: number): boolean {
  const overlap = Math.min(stop, f.kickoff + MATCH_S) - Math.max(start, f.kickoff)
  return start <= f.kickoff + LATE_S && overlap >= Math.min(30 * 60, (stop - start) / 2)
}

/**
 * What a programme is, by the fixtures: a live airing of [fixture] (several at once for a whip-around), or no live match of
 * ours — [rerun] when it names one of our fixtures but airs it at another time (a re-run or a preview); a match of a league
 * we don't follow is no match of ours but may well be live.
 */
export type Verdict = { live: true; comp: string; fixture?: Fixture } | { live: false; rerun: boolean }

/** Jev's answers by [title, candidate ids]: a fixture id, "several", "none", or "unsure". */
const jevAnswers = jsonFile<Record<string, string>>(
  () => process.env.FIXTURE_VERDICTS_FILE ?? fileURLToPath(new URL('../.cache/jev-fixtures.json', import.meta.url)), () => ({}))
const unjudged = new Map<string, { title: string; candidates: Fixture[] }>()
const KEEP_ANSWERS = 20_000

/**
 * What a programme titled [title] from [start] to [stop] is, by [fx]; undefined when the fixtures can't tell (an uncovered
 * competition-day, or a title Jev hasn't been asked about yet), so the guide's own rules decide. [comp] is the competition
 * the title names, if any. Titles the code reads: both teams known means it is their fixture, or no match of ours; one known
 * team with one fixture near means that fixture. Else, when the title names our competition or one of its teams is known,
 * the fixtures near it are Jev's to choose from. A youth game is never ours.
 */
export function fixtureVerdict(
  fx: Fixtures, title: string, start: number, stop: number, comp: string | undefined, jev = jevAnswers.read(), teamKey = espnTeamKey,
): Verdict | undefined {
  if (YOUTH.test(title)) return { live: false, rerun: false }
  if (!fx.covered(start, comp)) return undefined
  const candidates = fx.near(start - BEFORE_S, stop, comp)
  // No fixture: a re-run when the title names our competition (none of its matches is on), else maybe another league's match.
  const of = (f: Fixture | undefined): Verdict =>
    !f ? { live: false, rerun: !!comp } : airsLive(f, start, stop) ? { live: true, comp: f.comp, fixture: f } : { live: false, rerun: true }
  const has = (f: Fixture, key: string) => f.home.key === key || f.away.key === key
  const teams = (fixtureTeams(title)?.map(teamKey) ?? []).filter((k): k is string => !!k)
  if (teams.length === 2) return of(fx.near(start - BEFORE_S, stop + BEFORE_S).find((f) => has(f, teams[0]) && has(f, teams[1])))
  // One team read: its one fixture near, or none of ours when it has none ("CSI: Miami - …", "GEO Reportage - Kroatien").
  const withTeam = teams.length ? fx.near(start - BEFORE_S, stop + BEFORE_S).filter((f) => has(f, teams[0])) : []
  if (withTeam.length === 1) return of(withTeam[0])
  if (teams.length && !withTeam.length) return of(undefined)
  if (!candidates.length || (!comp && !teams.length)) return comp ? of(undefined) : undefined
  const key = JSON.stringify([title, candidates.map((f) => f.id).sort()])
  const answer = jev[key]
  if (answer === undefined) {
    unjudged.set(key, { title, candidates })
    return undefined
  }
  if (answer === 'several') {
    const on = candidates.filter((f) => airsLive(f, start, stop))
    const comps = new Set(on.map((f) => f.comp))
    return comps.size === 1 ? { live: true, comp: on[0].comp } : { live: false, rerun: false }
  }
  if (answer === 'unsure') return undefined
  return of(candidates.find((f) => f.id === answer))
}

/**
 * The fixture an event slot's name announces, its nearest to [now] (s): "UEFA | 02 - Latvia vs Armenia 2:00pm",
 * "Live | Spanien - England | UEFA Nations League | 8K EXCLUSIVE | DE: DAZN PPV 14". "stale" when the name's match can't be
 * one of ours on now, undefined when the fixtures can't tell (the slot stays as it was). By the name's fixture:
 * - both teams known: their fixture; none from yesterday to tomorrow is stale when both play only in leagues we read
 *   (last weekend's "Live Football 09 : Manchester United vs Crystal Palace 12:45 pm");
 * - one known ("Lille v KÍ Klaksvík"): its one fixture then; none is stale when it plays only in leagues we read;
 * - neither, the name naming our competition ("Potters Tigers FC vs. Green City FC | Antigua Premier League"): stale, as
 *   ESPN lists every team of a league we read.
 * A name without a fixture that names our competition: a club's channel ("UK: LA LIGA - ALAVES") is no match slot at all
 * (stale; it stays in its 🏆 group, Live now doesn't list it); anything else ("La Dazoneta 24 Horas | LALIGA EA SPORTS")
 * is stale while the competition has no fixture from yesterday to tomorrow. A team name ESPN doesn't list never hides a slot.
 */
export function slotFixture(
  fx: Fixtures, name: string, now = Date.now() / 1000, teamKey = espnTeamKey, teamLeagues = espnTeamLeagues,
): Fixture | 'stale' | undefined {
  const near = fx.near(now - 2 * DAY_S, now + 2 * DAY_S)
  const nearest = (found: Fixture[]) => found.sort((a, b) => Math.abs(a.kickoff - now) - Math.abs(b.kickoff - now))[0]
  const onlyOurs = (team: string) => !!teamLeagues(team)?.every((l) => l in LEAGUE_COMP)
  const comp = COMPS.find((c) => c.re.test(name) && !c.not?.test(name))?.key
  for (const part of name.split('|')) {
    const fixture = part.trim().replace(/^\d+\s*-\s*/, '').replace(/\s+\d{1,2}[:.]\d{2}\s*(?:[ap]m)?$/i, '')
    const teams = fixtureTeams(fixture)
    if (!teams) continue
    const keys = teams.map(teamKey)
    if (keys[0] && keys[1]) {
      if (keys[0] === keys[1]) continue
      const found = near.filter((f) => keys.includes(f.home.key) && keys.includes(f.away.key))
      if (found.length) return nearest(found)
      return teams.every(onlyOurs) && fx.covered(now) ? 'stale' : undefined
    }
    const i = keys.findIndex((k) => k)
    if (i >= 0) {
      const found = near.filter((f) => f.home.key === keys[i] || f.away.key === keys[i])
      if (found.length === 1) return found[0]
      return !found.length && onlyOurs(teams[i]) && fx.covered(now) ? 'stale' : undefined
    }
    return comp && fx.covered(now, comp) ? 'stale' : undefined
  }
  if (!comp) return undefined
  // A club's channel names one club after its competition: never a match slot.
  if (/\s[-–]\s[^-–|:]+$/.test(name) && /^[^|]*:/.test(name)) return 'stale'
  if (!fx.covered(now, comp)) return undefined
  return near.some((f) => f.comp === comp) ? undefined : 'stale'
}

/** A fixture's two teams as the app's crests table holds a match title's. */
export const fixtureCrests = (f: Fixture): TitleMatch =>
  ({ home: f.home.crest, away: f.away.crest, homeKey: f.home.key, awayKey: f.away.key, homeName: f.home.name, awayName: f.away.name })

const STATE = 'Football matches kicking off around the time a TV channel airs a programme, and the programme\'s title from the channel\'s guide.'
const clock = (s: number) => new Date(s * 1000).toISOString().slice(11, 16)
const question = (title: string, candidates: Fixture[]): JevQuestion => ({
  type: 'choice',
  instructions: `Which of these matches does the TV programme "${title}" show? Team names may be in another language or shortened.`,
  criteria: {
    ...Object.fromEntries(candidates.map((f) => [f.id, `${f.home.name} vs ${f.away.name}, kick-off ${clock(f.kickoff)} UTC`])),
    several: 'Several of these matches at once (a whip-around or conference)',
    none: 'None of them: another match, a show about football, or not football',
  },
})

let judging: Promise<void> | undefined
/** Has Jev place the titles builds met and couldn't; one run at a time. A run that fails leaves them for the next build. */
export function judgeFixtures(): Promise<void> {
  if (judging || !unjudged.size) return judging ?? Promise.resolve()
  const items = [...unjudged]
  unjudged.clear()
  judging = askJev(STATE, Object.fromEntries(items.map(([, v], i) => [`q${i}`, question(v.title, v.candidates)])), 12)
    .then(async (answers) => {
      await jevAnswers.update((cur) => {
        const all = { ...cur }
        items.forEach(([k], i) => {
          const a = answers[`q${i}`]
          if (!a?.choice) return
          // Only a sure choice places a title; an unsure one leaves it to the guide's rules.
          all[k] = (a.probabilities?.[a.choice] ?? 0) >= 0.8 ? a.choice : 'unsure'
        })
        const keys = Object.keys(all)
        for (const k of keys.slice(0, Math.max(0, keys.length - KEEP_ANSWERS))) delete all[k] // oldest first
        return all
      })
      fixturesVersion++
    })
    .catch((e) => console.warn(`fixture judge: ${e instanceof Error ? e.message : e}`))
    .finally(() => { judging = undefined })
  return judging
}
