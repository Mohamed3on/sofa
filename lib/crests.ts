// Football crests for the app's match rows: a guide title's two teams ("Man United v Aston Villa") resolved to
// TheSportsDB badges (free key 123, under ten calls a minute). Lookups are kept on disk, found for 30 days, none for
// one; the guide build only reads what is kept and queues the rest, so a new team's crest shows from a later build.
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
  '1899 hoffenheim': 'hoffenheim', celta: 'celta vigo', havre: 'le havre', sporting: 'sporting cp', cray: 'cray wanderers',
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

/** [team]'s crest as kept, queueing a lookup when there is none yet or it is due again. */
export function crestOf(team: string): string | null {
  const key = crestKey(team)
  if (!key) return null
  const k = load()[key]
  if (!k || Date.now() - k.at > (k.url ? FOUND_MS : NONE_MS)) enqueue(key)
  return k?.url ?? null
}

/** A match title's crests, home and away, as kept; null when neither is. */
export function titleCrests(title: string): [string | null, string | null] | null {
  const teams = fixtureTeams(title)
  if (!teams) return null
  const crests = teams.map(crestOf) as [string | null, string | null]
  return crests[0] || crests[1] ? crests : null
}

function enqueue(key: string) {
  if (process.env.NODE_ENV === 'test' || queue.has(key)) return
  queue.add(key)
  if (!working) void work()
}

async function work() {
  working = true
  try {
    for (const key of queue) {
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
