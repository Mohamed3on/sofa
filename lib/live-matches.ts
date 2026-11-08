// Today's football as the app's match rows show it: score and clock ("67'", "HT", "FT", kick-off "20:00"), from ESPN's
// keyless scoreboards for the leagues the crests come from (ESPN_LEAGUES). Asked only while the app asks (it polls on
// live TV), at most every 30 s while a match is on or due, else every 10 minutes; kept in memory.
import { espnKey, ESPN_LEAGUES, type EspnTeam } from './crests.js'
import { getJson } from './http.js'

/** A match's state for the app: home and away score, ESPN's state (pre, in, post) and the clock it shows. */
export interface MatchState { hs: number; as: number; state: 'pre' | 'in' | 'post'; clock: string }

export interface EspnEvent {
  date: string
  status: { displayClock?: string; type: { state: string; name?: string; completed?: boolean; shortDetail?: string } }
  competitions: { competitors: { homeAway: string; score?: string; team: EspnTeam }[] }[]
}

const LIVE_MS = 30_000
const IDLE_MS = 600_000
/** Matches further from now than these are some other day's, which a cup's scoreboard can show. */
const BEFORE_MS = 8 * 3600_000
const AHEAD_MS = 16 * 3600_000
const kickoff = new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Berlin', hour: '2-digit', minute: '2-digit', hour12: false })

/** An ESPN event as ["homeKey|awayKey", state]; null for one postponed, called off, or not today's. */
export function matchState(e: EspnEvent, now = Date.now()): [string, MatchState] | null {
  const teams = e.competitions?.[0]?.competitors ?? []
  const home = teams.find((t) => t.homeAway === 'home')
  const away = teams.find((t) => t.homeAway === 'away')
  const at = Date.parse(e.date)
  if (!home || !away || !(at > now - BEFORE_MS && at < now + AHEAD_MS)) return null
  const { type } = e.status
  const clock = type.state === 'pre' ? kickoff.format(at)
    : type.state === 'in' ? (type.name === 'STATUS_HALFTIME' ? 'HT' : e.status.displayClock || '')
      : type.state === 'post' && type.completed ? (type.shortDetail || 'FT') : ''
  if (!clock) return null
  return [`${espnKey(home.team)}|${espnKey(away.team)}`, { hs: Number(home.score) || 0, as: Number(away.score) || 0, state: type.state as MatchState['state'], clock }]
}

let kept: { at: number; matches: Record<string, MatchState>; live: boolean } = { at: 0, matches: {}, live: false }
let loading: Promise<void> | null = null

async function load() {
  const events: EspnEvent[] = []
  let ok = 0
  for (const league of ESPN_LEAGUES) {
    try {
      events.push(...(await getJson<{ events?: EspnEvent[] }>(`https://site.api.espn.com/apis/site/v2/sports/soccer/${league}/scoreboard`, { timeoutMs: 8000, retries: 0 })).events ?? [])
      ok++
    } catch (e) {
      console.warn(`matches: ESPN ${league}: ${e instanceof Error ? e.message : e}`)
    }
  }
  const now = Date.now()
  const matches: Record<string, MatchState> = {}
  for (const e of events) {
    const m = matchState(e, now)
    if (m) matches[m[0]] = m[1]
  }
  // On while one is in play or past its kick-off; a run that reached no league keeps the last answer.
  const live = events.some((e) => e.status.type.state === 'in' || (e.status.type.state === 'pre' && Date.parse(e.date) <= now))
  if (ok) kept = { at: now, matches, live }
  else kept = { ...kept, at: now }
}

/** Today's matches by "homeKey|awayKey"; waits for the first scoreboards, then answers at once while newer ones load. */
export async function liveMatches(): Promise<Record<string, MatchState>> {
  if (Date.now() - kept.at > (kept.live ? LIVE_MS : IDLE_MS) && !loading) loading = load().finally(() => (loading = null))
  if (kept.at === 0 && loading) await loading
  return kept.matches
}
