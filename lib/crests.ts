// Football crests for the app's match rows: a guide title's two teams ("Man United v Aston Villa") resolved to a crest.
// ESPN's public team lists first: one keyless call a league gives every team's crest (21 leagues and cups, ~900 teams
// in seconds), kept on disk and read again weekly. A team none of them lists (a cup tie's non-league side) falls back
// to TheSportsDB (free key 123, under ten calls a minute), kept found for 30 days, none for one. The guide build only
// reads what is kept and queues the rest, so a crest still missing shows from a later build.
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { writeAtomic } from './fs.js'
import { getJson, HttpError } from './http.js'

const FOUND_MS = 30 * 864e5
const NONE_MS = 864e5
/** Between two lookups: TheSportsDB's free key answers 429 for minutes after about ten quick ones. */
const GAP_MS = 6500
const BACKOFF_MS = 120_000
const file = () => process.env.CRESTS_FILE ?? fileURLToPath(new URL('../.cache/crests.json', import.meta.url))

type Kept = Record<string, { at: number; url: string | null }>

/** ESPN's soccer leagues and cups the household's competitions play in; top leagues first, so a shared short name keeps theirs. */
const ESPN_LEAGUES = ['eng.1', 'esp.1', 'ger.1', 'ita.1', 'fra.1', 'uefa.champions', 'uefa.europa', 'uefa.europa.conf', 'eng.2', 'por.1', 'ned.1',
  'sco.1', 'tur.1', 'ksa.1', 'usa.1', 'eng.fa', 'eng.league_cup', 'esp.copa_del_rey', 'ger.dfb_pokal', 'ita.coppa_italia', 'fra.coupe_de_france']
const ESPN_MS = 7 * 864e5
const espnFile = () => process.env.CRESTS_ESPN_FILE ?? fileURLToPath(new URL('../.cache/crests-espn.json', import.meta.url))
interface EspnTeam { displayName?: string; shortDisplayName?: string; name?: string; location?: string; logos?: { href?: string }[] }

/** Team key → crest, from ESPN's lists ([espnTeams] builds it); loaded from disk on first use, fetched again when a week old. */
let espn: { at: number; teams: Record<string, string> } | null = null
let espnLoading = false

/** Every name an ESPN team goes by, as a key, to a small (64 px) crest; the first league to name a key keeps it. */
export function espnTeams(lists: EspnTeam[][]): Record<string, string> {
  const out: Record<string, string> = {}
  for (const teams of lists) for (const t of teams) {
    const href = t.logos?.[0]?.href
    if (!href) continue
    const url = `https://a.espncdn.com/combiner/i?img=${new URL(href).pathname}&w=64&h=64`
    for (const n of [t.displayName, t.shortDisplayName, t.name, t.location]) {
      const k = n ? crestKey(n) : ''
      if (k && !(k in out)) out[k] = url
    }
  }
  return out
}

function espnCrest(key: string): string | null {
  if (!espn) {
    try { espn = JSON.parse(readFileSync(espnFile(), 'utf8')) } catch { espn = { at: 0, teams: {} } }
  }
  if (Date.now() - espn!.at > ESPN_MS && !espnLoading && process.env.NODE_ENV !== 'test') void loadEspn()
  return espn!.teams[key] ?? null
}

async function loadEspn() {
  espnLoading = true
  try {
    const lists: EspnTeam[][] = []
    for (const league of ESPN_LEAGUES) {
      try {
        const res = await getJson<{ sports?: { leagues?: { teams?: { team: EspnTeam }[] }[] }[] }>(`https://site.api.espn.com/apis/site/v2/sports/soccer/${league}/teams`, { timeoutMs: 10_000, retries: 1 })
        lists.push((res.sports?.[0]?.leagues?.[0]?.teams ?? []).map((t) => t.team))
      } catch (e) {
        console.warn(`crests: ESPN ${league}: ${e instanceof Error ? e.message : e}`)
      }
    }
    const teams = espnTeams(lists)
    console.log(`crests: ESPN lists ${lists.length}/${ESPN_LEAGUES.length} leagues, ${Object.keys(teams).length} names`)
    // A failed run keeps the last lists and tries again in an hour, not a week.
    if (Object.keys(teams).length < 200) { espn = { at: Date.now() - ESPN_MS + 3_600_000, teams: { ...espn?.teams, ...teams } }; return }
    espn = { at: Date.now(), teams }
    await writeAtomic(espnFile(), espn)
  } finally {
    espnLoading = false
  }
}

/** Before a guide build: ESPN's lists once there are none on disk yet (a first start), a few seconds, so that build already has them. */
export async function crestsReady() {
  espnCrest('')
  if (espn!.at === 0 && !espnLoading && process.env.NODE_ENV !== 'test') await loadEspn()
}
interface Team { strTeam: string; strTeamAlternate?: string | null; strSport?: string; strGender?: string; strBadge?: string | null }

const SUPERSCRIPT = /[ᴬ-ᶿ⁰-₟²³¹]+/g
const LIVE_PREFIX = /^live\b\s*[:-]?\s*/i
const VERSUS = /\s(?:v|vs\.?|x)\s/i
/** Words a broadcaster puts round a fixture that never name a team ("LaLiga", "Week 10", "PL"). */
const NOT_TEAM = /^(?:live|pl|epl|ucl|uel|uecl|la ?liga.*|premier league.*|.*\bleague\b.*|serie a.*|bundesliga.*|ligue 1.*|fa cup.*|efl cup.*|copa .*|coppa .*|dfb.pokal.*|.*\b(?:week|matchday|jornada|giornata|round|spieltag)\b.*|\d{2,4}\/\d{2,4}.*)$/i
/** A competition named before the home team without a colon: "Live Serie A Fiorentina v Juventus". */
const COMP_PREFIX = /^(?:serie a|fa cup|premier league|pl|la ?liga|bundesliga|ligue 1|ucl|uel)\s+/i
/** Club-form words dropped from a key: "Getafe CF" and "Getafe" are one team; "1. FC Union Berlin" and "Union Berlin" too. */
const CLUB_WORDS = new Set(['fc', 'cf', 'sc', 'ac', 'afc', 'ssc', 'cd', 'ud', 'sd', 'rc', 'ogc', 'sco', 'sv', 'vfb', 'vfl', 'tsg', 'bc', 'ss', 'us', 'as', 'calcio', 'club', 'de', 'futbol', '1'])
/** Short or foreign names TheSportsDB doesn't know, as the name it does. */
const ALIASES: Record<string, string> = {
  'man united': 'manchester united', 'man utd': 'manchester united', 'manchester utd': 'manchester united', 'man city': 'manchester city',
  spurs: 'tottenham hotspur', tottenham: 'tottenham hotspur', wolves: 'wolverhampton wanderers', 'nottm forest': 'nottingham forest',
  newcastle: 'newcastle united', 'west ham': 'west ham united', brighton: 'brighton and hove albion', leeds: 'leeds united',
  'c palace': 'crystal palace', 'sheff wed': 'sheffield wednesday', 'sheffield wed': 'sheffield wednesday', 'sheff utd': 'sheffield united',
  inter: 'inter milan', internazionale: 'inter milan', milan: 'ac milan', barcellona: 'barcelona', nizza: 'nice', 'paris saint germain': 'paris sg', psg: 'paris sg',
  bayern: 'bayern munich', 'bayern munchen': 'bayern munich', 'bayern monaco': 'bayern munich', atletico: 'atletico madrid', 'atl madrid': 'atletico madrid',
  gladbach: 'borussia monchengladbach', monchengladbach: 'borussia monchengladbach', dortmund: 'borussia dortmund', 'bayer 04 leverkusen': 'bayer leverkusen',
  '1899 hoffenheim': 'hoffenheim', athletic: 'athletic bilbao', celta: 'celta vigo', havre: 'le havre', sporting: 'sporting cp', cray: 'cray wanderers',
}

/** "Man Utd FC" → "manchester united": accents and club-form words gone, lower case, known short names expanded. */
export function crestKey(team: string): string {
  const words = team.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/&/g, ' and ').replace(/[^\p{L}\p{N}]+/gu, ' ').trim().split(' ')
  const key = words.map((w) => (w === 'utd' ? 'united' : w)).filter((w) => w && !CLUB_WORDS.has(w)).join(' ')
  return ALIASES[key] ?? key
}

/**
 * The two teams of a match title, home first, as the app shows its fixture: "Premier League: Arsenal vs Hull City",
 * "Athletic Bilbao vs Real Sociedad - Spanish LaLiga - Week 11", "Live: LaLiga - Celta Vigo - Levante". Null for a
 * title that is no fixture or can't be read as two teams.
 */
export function fixtureTeams(title: string): [string, string] | null {
  let t = title.replace(SUPERSCRIPT, '').replace(LIVE_PREFIX, '').trim()
  const tail = t.slice(t.lastIndexOf(':') + 1).trim()
  if (t.includes(':') && (tail.includes(' - ') || VERSUS.test(tail))) t = tail
  // A part runs to its first comma ("AFC Bournemouth, 10. Spieltag"); one with a colon left in is no team.
  const parts = t.split(/\s+[-–—]\s+/).map((p) => p.split(',')[0].trim()).filter(Boolean)
  const versus = parts.find((p) => VERSUS.test(p))
  const teams = (versus ? versus.split(VERSUS) : parts.filter((p) => !NOT_TEAM.test(p))).slice(0, 2)
  if (teams.length !== 2 || teams.some((p) => p.includes(':'))) return null
  const [home, away] = teams.map((s) => s.replace(/\s*\([^)]*\)\s*$/, '').trim())
  if (COMP_PREFIX.test(home)) return fixtureTeams(home.replace(COMP_PREFIX, '') + ' v ' + away)
  return home && away && crestKey(home) && crestKey(away) ? [home, away] : null
}

/**
 * TheSportsDB's football team for [key]: one named that (or so by an alternate name), else the only one whose name
 * is [key] plus words ("grimsby" → Grimsby Town) or [key] less them; null when there is no such team or it's unclear.
 */
export function pickCrest(key: string, teams: Team[]): string | null {
  const names = (t: Team) => [t.strTeam, ...(t.strTeamAlternate ?? '').split(',')].map((n) => crestKey(n.trim())).filter(Boolean)
  const football = teams.filter((t) => t.strSport === 'Soccer' && t.strBadge && (/\bwomen\b/.test(key) || t.strGender !== 'Female'))
  const exact = football.find((t) => names(t).includes(key))
  if (exact) return exact.strBadge!
  const near = football.filter((t) => names(t).some((n) => n.startsWith(key + ' ') || key.startsWith(n + ' ')))
  return near.length === 1 ? near[0].strBadge! : null
}

let kept: Kept | null = null
const queue = new Set<string>()
let working = false

function load(): Kept {
  if (!kept) {
    try { kept = JSON.parse(readFileSync(file(), 'utf8')) as Kept } catch { kept = {} }
  }
  return kept
}

/** [team]'s crest as kept (a due one keeps showing while it's asked again), queueing a lookup when there is none yet or it is due; a match [onNow] goes to the front. */
export function crestOf(team: string, onNow = false): string | null {
  const key = crestKey(team)
  if (!key) return null
  const listed = espnCrest(key)
  if (listed) return listed
  const k = load()[key]
  // While ESPN's lists are still loading, wait for them rather than spend TheSportsDB's slow calls on teams they have.
  if ((!k || Date.now() - k.at > (k.url ? FOUND_MS : NONE_MS)) && !espnLoading) enqueue(key, onNow)
  return k?.url ?? null
}

/** A match title's crests, home and away, as kept; null when neither is. A match [onNow] is looked up before later ones. */
export function titleCrests(title: string, onNow = false): [string | null, string | null] | null {
  const teams = fixtureTeams(title)
  if (!teams) return null
  const crests = teams.map((t) => crestOf(t, onNow)) as [string | null, string | null]
  return crests[0] || crests[1] ? crests : null
}

function enqueue(key: string, first: boolean) {
  if (process.env.NODE_ENV === 'test' || (queue.has(key) && !first)) return
  if (first) {
    // Matches on now jump the queue: the one being watched shows its crests a guide build later, not after every other team.
    const rest = [...queue].filter((k) => k !== key)
    queue.clear()
    queue.add(key)
    for (const k of rest) queue.add(k)
  } else queue.add(key)
  if (!working) void work()
}

async function work() {
  working = true
  try {
    while (queue.size) {
      const key = queue.values().next().value!
      const url = await lookup(key)
      queue.delete(key)
      if (url !== undefined) {
        load()[key] = { at: Date.now(), url }
        await writeAtomic(file(), kept)
      }
      await Bun.sleep(GAP_MS)
    }
  } finally {
    working = false
  }
}

/** The crest for [key], null when TheSportsDB has none, undefined when it couldn't be asked now (after backing off). */
async function lookup(key: string): Promise<string | null | undefined> {
  try {
    const res = await getJson<{ teams: Team[] | null }>(`https://www.thesportsdb.com/api/v1/json/123/searchteams.php?t=${encodeURIComponent(key)}`, { timeoutMs: 8000, retries: 0 })
    return pickCrest(key, res.teams ?? [])
  } catch (e) {
    console.warn(`crests: ${key}: ${e instanceof Error ? e.message : e}`)
    if (e instanceof HttpError && e.status === 429) await Bun.sleep(BACKOFF_MS)
    return undefined
  }
}
