// Which channels show a match, from per-country TV listings sites (one family of sites, one page layout,
// no bot wall): Spain, Germany, the UK, the Arab world, and the countries whose channels are kept abroad
// (curation.ts ABROAD, each country's hidden "🌍 XX" bucket). The guides often lie about a match: Movistar
// LaLiga lists "Movistar LaLiga UHD" all evening while it shows Atlético vs Barcelona, DAZN 2 a bare
// "LaLiga 26/27", and the provider's LaLiga TV lists a match that is on Premier Sports. So a match a
// country's site gives a channel is put in that channel's guide, and a match the guide gives a channel
// the site knows, but not for that match, is taken out.
import { fileURLToPath } from 'node:url'
import { chanKey, couldBeLiveMatch, teamWords, unxml, type Channel } from './curation.js'
import { jsonFile } from './fs.js'
import { askJev } from './jev.js'

/** [home]: the group(s) of the site's country, whose channels its names map to; [mdy]: month-first dates. */
interface Site { url: string; home: string | string[]; tz: string; mdy?: true }
// Timezones checked against futbolenlatv.es's kick-offs (2026-11-08).
const SITES: Site[] = [
  { url: 'https://www.futbolenlatv.es/', home: '🇪🇸 Spanish Sports', tz: 'Europe/Madrid' },
  { url: 'https://www.fussball-im-tv.com/', home: '🇩🇪 German Sports', tz: 'Europe/Berlin' },
  { url: 'https://www.ukfootballontv.co.uk/', home: '🏴 Sky Sports & UK Sports', tz: 'Europe/London' },
  // its kick-offs are on Saudi time (Atlético vs Barcelona, 20:00 UTC, shows 23:00)
  { url: 'https://www.livefootballtv.info/', home: '🇸🇦 beIN & Arabic Sports', tz: 'Asia/Riyadh' },
  { url: 'https://www.calcio-tv.com/', home: '🇮🇹 Italian Sports', tz: 'Europe/Rome' },
  { url: 'https://www.foottv.fr/', home: ['🇫🇷 French Sports', '🌍 FR'], tz: 'Europe/Paris' },
  { url: 'https://www.futebolnatv.pt/', home: '🌍 PT', tz: 'Europe/Lisbon' },
  { url: 'https://www.voetbalvandaag-tv.nl/', home: '🌍 NL', tz: 'Europe/Amsterdam' },
  { url: 'https://www.fussball-im-tv.at/', home: '🌍 AT', tz: 'Europe/Vienna' },
  { url: 'https://www.fussball-im-tv.ch/', home: '🌍 CH', tz: 'Europe/Zurich' },
  { url: 'https://www.footballtv.pl/', home: '🌍 PL', tz: 'Europe/Warsaw' },
  { url: 'https://www.fotbaldnestv.cz/', home: '🌍 CZ', tz: 'Europe/Prague' },
  { url: 'https://www.fodboldidag.dk/', home: '🌍 DK', tz: 'Europe/Copenhagen' },
  { url: 'https://www.fotbollpatvidag.se/', home: '🌍 SE', tz: 'Europe/Stockholm' },
  { url: 'https://www.fotballtv.com/', home: '🌍 NO', tz: 'Europe/Oslo' },
  { url: 'https://www.sportsontvusa.com/', home: '🌍 US', tz: 'America/New_York', mdy: true },
  { url: 'https://www.futbolenvivousa.com/', home: '🌍 US', tz: 'America/New_York', mdy: true },
  { url: 'https://www.sportsguidetv.com/', home: '🌍 CA', tz: 'America/Toronto' },
  { url: 'https://www.futebolaovivobrasil.com/', home: '🌍 BR', tz: 'America/Sao_Paulo' },
]

/** A match as one site lists it: its teams, competition, kick-off (ms) and channel names. */
export interface Listing { home: string; away: string; comp: string; start: number; channels: string[] }

// A match runs 1h45 by the sites; a guide gives it 2 h, half-time and stoppage included.
const MATCH_MS = 2 * 3600_000

/** Ms since the epoch of a wall-clock time in [tz]. */
function zoned(y: number, mo: number, d: number, h: number, mi: number, tz: string): number {
  const utc = Date.UTC(y, mo - 1, d, h, mi)
  const p = Object.fromEntries(new Intl.DateTimeFormat('en-GB', { timeZone: tz, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
    .formatToParts(utc).map((x) => [x.type, Number(x.value)]))
  return utc - (Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute) - utc)
}

const unescape = (s: string) => unxml(s).trim()

/** A listings page's matches: day headers (day first, "08/11/2026", "08.11.2026", "8-11-2026"; year first, "2026-11-08";
 *  month first on the US sites, [mdy]), competition headers, then a row per match. */
export function parseListings(html: string, tz: string, mdy = false): Listing[] {
  const out: Listing[] = []
  let day: number[] | null = null
  let comp = ''
  for (const tr of html.split(/<tr\b/).slice(1)) {
    if (/^[^>]*class="cabeceraTabla/.test(tr)) {
      const m = tr.match(/\b(\d{1,4})[/.-](\d{1,2})[/.-](\d{2,4})\b/)?.slice(1).map(Number)
      day = !m ? null : m[0] > 999 ? m : mdy ? [m[2], m[0], m[1]] : [m[2], m[1], m[0]]
      if (day && day[0] < 100) day[0] += 2000 // "8-11-26" is 2026, not 1926
    } else if (/^[^>]*class="cabeceraCompericion/.test(tr)) {
      comp = unescape(tr.match(/title="([^"]*)"/)?.[1] ?? '')
    } else if (day) {
      const time = tr.match(/<td class="hora[^"]*">\s*(\d\d):(\d\d)/)
      const teams = tr.match(/itemprop="name" content="([^"]*?) - ([^"]*)"/)
      const channels = [...(tr.match(/<ul class="listaCanales">([\s\S]*?)<\/ul>/)?.[1] ?? '').matchAll(/<li[^>]*title="([^"]+)"/g)].map((m) => unescape(m[1]))
      if (!time || !teams || !channels.length) continue
      out.push({ home: unescape(teams[1]), away: unescape(teams[2]), comp, start: zoned(day[0], day[1], day[2], Number(time[1]), Number(time[2]), tz), channels })
    }
  }
  return out
}

// A site's channel name as the family key it would have: no codes in brackets ("M+ LALIGA (M54 O110)"),
// no "…: VER PARTIDO", Movistar's "M+" spelled out, "EN"/"FR" feeds by their language, words in any order.
// Accents go ("CAZÉTV"); "Ligue 1+ 5" is the provider's "LIGUE1+ 5", "TV 2" its "TV2", Viaplay's renamed "V Sport" (Norway's
// "Viasport") its "VIAPLAY SPORT", the provider's "DISNEY+ ESPN" and "VIAPLAY TV3 SPORT" the sites' "ESPN" and "TV3 Sport".
const sorted = (s: string) =>
  chanKey(s.normalize('NFD').replace(/\p{M}/gu, '')).replace(/\bla liga\b/g, 'laliga').replace(/\bligue 1\b/g, 'ligue1').replace(/\btv (\d)\b/g, 'tv$1').replace(/ ultra hd$/, '').replace(/^viasport\b/, 'v sport')
    .replace(/^v sport football$/, 'viaplay fotboll').replace(/^v sport\b/, 'viaplay sport').replace(/^(?:disney|viaplay) (?=espn|tv\d)/, '')
    .split(' ').sort().join(' ')
const siteKeys = (name: string): string[] => {
  if (/\(turkey\)/i.test(name)) return [] // beIN Türkiye, not the Arab beIN
  const s = name.replace(/\(.*?\)/g, '').replace(/:.*$/, '').replace(/\bEN$/, 'english').replace(/\bFR$/, 'france').replace(/ Direct$/i, '').trim()
  return /^m\+/i.test(s) ? [sorted(s.replace(/^m\+\s*/i, 'movistar ')), sorted(s.replace(/^m\+\s*/i, ''))] : [sorted(s)]
}

/** Each listed match with the tvg-ids of the channels it names; [known] is every tvg-id the site names that day. */
export interface Placed { title: string; start: number; sides: [string[], string[]]; ids: Set<string>; known: Set<string> }

/** [listings] by site, placed on [channels] (their home groups as the site's country). */
export function placeListings(channels: Channel[], listings: { home: string | string[]; listings: Listing[] }[]): Placed[] {
  const ids = new Map<string, Set<string>>() // home|sorted key -> tvg-ids
  for (const c of channels) {
    if (c.isEventSlot || !c.tvgId) continue
    const k = `${c.group}|${sorted(c.name)}`
    ;(ids.get(k) ?? ids.set(k, new Set()).get(k)!).add(c.tvgId)
  }
  return listings.flatMap(({ home, listings }) => {
    const idsOf = (name: string) => [home].flat().flatMap((h) => siteKeys(name).flatMap((k) => [...(ids.get(`${h}|${k}`) ?? [])]))
    const known = new Set(listings.flatMap((l) => l.channels.flatMap(idsOf)))
    return listings.map((l) => ({
      title: `${l.home} vs ${l.away}${l.comp ? ` - ${l.comp}` : ''}`,
      start: l.start,
      sides: [teamWords(l.home), teamWords(l.away)] as [string[], string[]],
      ids: new Set(l.channels.flatMap(idsOf)),
      known,
    })).filter((p) => p.ids.size && p.sides[0].length && p.sides[1].length)
  })
}

// One team's word in another spelling: "atl" for "atletico", "barcelonne" for "barcelona".
const sameWord = (a: string, b: string) => a.startsWith(b) || b.startsWith(a) || (a.length >= 6 && b.length >= 6 && a.slice(0, 6) === b.slice(0, 6))
const named = (title: string, sides: [string[], string[]]) => {
  const w = teamWords(title)
  return sides.map((side) => side.some((s) => w.some((t) => sameWord(s, t))))
}
const names = (title: string, sides: [string[], string[]]) => named(title, sides).every(Boolean)
// A fixture ("X v Y", "X - Y", "X × Y") whose teams the words can't settle against [sides]: it names one side
// (the other spelled another way), or it is in another script, where no word can match.
const FIXTURE = /\S\s+(?:v|vs\.?|x|×|-|–|—|@)\s+\S|\S×\S/i
const OTHER_SCRIPT = /[\p{Script=Arabic}\p{Script=Greek}\p{Script=Cyrillic}\p{Script=Hebrew}\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}]/u
const unsettled = (title: string, sides: [string[], string[]]) =>
  couldBeLiveMatch(title) && FIXTURE.test(title) && (OTHER_SCRIPT.test(title) || named(title, sides).some(Boolean))

/** Whether a guide programme and a listed match airing at the same time are one match: true or false when known, else undefined. */
export type SameMatch = (programme: string, listed: string) => boolean | undefined

/**
 * A channel's programmes ([blocks], one channel's, with its [at] reader and [make] writer) with the
 * listings applied: a match the site gives it and its guide lacks is added over what was there, and a
 * match its guide gives it that the site lists elsewhere, while knowing the channel, is dropped. A programme
 * names the listed match when it names both sides; one whose team words settle nothing (another spelling or
 * script, "بايرن ميونخ × دورتموند") and that names no other listed match is the listed match when [same] says so.
 */
export function applyListings(
  id: string,
  blocks: string[],
  placed: Placed[],
  at: (b: string, k: 'start' | 'stop') => number,
  title: (b: string) => string,
  make: (start: number, stop: number, title: string) => string,
  retime: (b: string, start: number, stop: number) => string,
  same: SameMatch = () => undefined,
): string[] {
  let out = blocks
  for (const p of placed) {
    if (!p.ids.has(id) && !p.known.has(id)) continue
    const stop = p.start + MATCH_MS
    const during = (b: string) => at(b, 'start') < stop && at(b, 'stop') > p.start
    const other = (t: string) => placed.some((q) => q !== p && Math.abs(q.start - p.start) < MATCH_MS && names(t, q.sides) && !names(q.title, p.sides))
    const shown = out.filter((b) => {
      if (!during(b)) return false
      const t = title(b)
      return names(t, p.sides) || (unsettled(t, p.sides) && !other(t) && same(t, p.title) === true)
    })
    if (p.ids.has(id)) {
      if (shown.length) continue
      out = out.flatMap((b) => {
        const s = at(b, 'start'), e = at(b, 'stop')
        if (!(s < stop && e > p.start)) return [b]
        const keep: string[] = []
        if (p.start - s >= 5 * 60_000) keep.push(retime(b, s, p.start))
        if (e - stop >= 5 * 60_000) keep.push(retime(b, stop, e))
        return keep
      })
      out.push(make(p.start, stop, p.title))
    } else if (p.known.has(id) && shown.length) {
      // unless another row of the same match lists it (a site can list one match twice, with other channels)
      const mine = placed.filter((q) => q.ids.has(id) && Math.abs(q.start - p.start) < MATCH_MS)
      out = out.filter((b) => !shown.includes(b) || mine.some((q) => names(title(b), q.sides)))
    }
  }
  return out
}

// Jev's verdicts on [SameMatch] pairs, by [programme, listed] (a Noul: 0.7 and over is one match, 0.3 and under
// two, between unsettled, which leaves the guide as the team words had it). A guide build asks nothing:
// it takes the verdicts it has and notes the pairs it lacks, judged after it (judgeSameMatches) for the next build.
const verdicts = jsonFile<Record<string, number>>(
  () => process.env.SAME_MATCH_FILE ?? fileURLToPath(new URL('../.cache/jev-same-match.json', import.meta.url)), () => ({}))
const unjudged = new Map<string, [string, string]>()
const KEEP_VERDICTS = 20_000

export const sameMatch: SameMatch = (programme, listed) => {
  const k = JSON.stringify([programme, listed])
  const v = verdicts.read()[k]
  if (v === undefined) unjudged.set(k, [programme, listed])
  return v === undefined || (v > 0.3 && v < 0.7) ? undefined : v >= 0.7
}

const STATE = 'A football match listed by a TV listings site for some channel, and a programme in another channel\'s TV guide airing at the same time.'
const sameQuestion = (programme: string, listed: string) => ({
  type: 'noul' as const,
  instructions: `Is the guide programme "${programme}" the listed match "${listed}"?`,
  criteria: {
    true: 'Both name the same two teams, in any language, spelling or order',
    false: 'They name at least one different team, or the programme is not a live match of those teams',
  },
})

let judging: Promise<void> | undefined
/** Has Jev judge the pairs builds have noted and not had judged; one run at a time. A failed run's pairs come back next build. */
export function judgeSameMatches(): Promise<void> {
  if (judging || !unjudged.size) return judging ?? Promise.resolve()
  const pairs = [...unjudged]
  unjudged.clear()
  judging = askJev(STATE, Object.fromEntries(pairs.map(([, [p, l]], i) => [`q${i}`, sameQuestion(p, l)])))
    .then(async (answers) => {
      const all = { ...verdicts.read(true) }
      pairs.forEach(([k], i) => { const n = answers[`q${i}`]?.noul; if (typeof n === 'number') all[k] = n })
      const keys = Object.keys(all)
      for (const k of keys.slice(0, Math.max(0, keys.length - KEEP_VERDICTS))) delete all[k] // oldest first
      await verdicts.write(all)
    })
    .catch((e) => console.warn(`same-match judge: ${e instanceof Error ? e.message : e}`))
    .finally(() => { judging = undefined })
  return judging
}

const LISTINGS_TTL = 2 * 3600_000
let cache: { at: number; job: Promise<{ home: string | string[]; listings: Listing[] }[]> } | undefined

/** Every site's matches, fetched at most every 2 h; a site that fails is left out until the next try. */
export function fetchListings(): Promise<{ home: string | string[]; listings: Listing[] }[]> {
  if (!cache || Date.now() - cache.at > LISTINGS_TTL) {
    const job = Promise.all(SITES.map(async (s) => {
      try {
        const res = await fetch(s.url, { headers: { 'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36' }, signal: AbortSignal.timeout(15_000) })
        return { home: s.home, listings: res.ok ? parseListings(await res.text(), s.tz, s.mdy) : [] }
      } catch {
        return { home: s.home, listings: [] }
      }
    }))
    cache = { at: Date.now(), job }
  }
  return cache.job
}
