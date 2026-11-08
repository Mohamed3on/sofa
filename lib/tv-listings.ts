// Which channels show a match, from per-country TV listings sites (one family of sites, one page layout,
// no bot wall): Spain, Germany, the UK and the Arab world. The guides often lie about a match: Movistar
// LaLiga lists "Movistar LaLiga UHD" all evening while it shows Atlético vs Barcelona, DAZN 2 a bare
// "LaLiga 26/27", and the provider's LaLiga TV lists a match that is on Premier Sports. So a match a
// country's site gives a channel is put in that channel's guide, and a match the guide gives a channel
// the site knows, but not for that match, is taken out.
import { chanKey, teamWords, unxml, type Channel } from './curation.js'

interface Site { url: string; home: string; tz: string }
const SITES: Site[] = [
  { url: 'https://www.futbolenlatv.es/', home: '🇪🇸 Spanish Sports', tz: 'Europe/Madrid' },
  { url: 'https://www.fussball-im-tv.com/', home: '🇩🇪 German Sports', tz: 'Europe/Berlin' },
  { url: 'https://www.ukfootballontv.co.uk/', home: '🏴 Sky Sports & UK Sports', tz: 'Europe/London' },
  // its kick-offs are on Saudi time (Atlético vs Barcelona, 20:00 UTC, shows 23:00)
  { url: 'https://www.livefootballtv.info/', home: '🇸🇦 beIN & Arabic Sports', tz: 'Asia/Riyadh' },
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

/** A listings page's matches: day headers ("dd/mm/yyyy", the German site's "dd.mm.yyyy"), competition headers, then a row per match. */
export function parseListings(html: string, tz: string): Listing[] {
  const out: Listing[] = []
  let day: number[] | null = null
  let comp = ''
  for (const tr of html.split(/<tr\b/).slice(1)) {
    if (/^[^>]*class="cabeceraTabla/.test(tr)) {
      const m = tr.match(/(\d\d)[/.](\d\d)[/.](\d{4})/)
      day = m ? [Number(m[3]), Number(m[2]), Number(m[1])] : null
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
const sorted = (s: string) => chanKey(s).replace(/\bla liga\b/g, 'laliga').replace(/ ultra hd$/, '').split(' ').sort().join(' ')
const siteKeys = (name: string): string[] => {
  if (/\(turkey\)/i.test(name)) return [] // beIN Türkiye, not the Arab beIN
  const s = name.replace(/\(.*?\)/g, '').replace(/:.*$/, '').replace(/\bEN$/, 'english').replace(/\bFR$/, 'france').trim()
  return /^m\+/i.test(s) ? [sorted(s.replace(/^m\+\s*/i, 'movistar ')), sorted(s.replace(/^m\+\s*/i, ''))] : [sorted(s)]
}

/** Each listed match with the tvg-ids of the channels it names; [known] is every tvg-id the site names that day. */
export interface Placed { title: string; start: number; sides: [string[], string[]]; ids: Set<string>; known: Set<string> }

/** [listings] by site, placed on [channels] (their home groups as the site's country). */
export function placeListings(channels: Channel[], listings: { home: string; listings: Listing[] }[]): Placed[] {
  const ids = new Map<string, Set<string>>() // home|sorted key -> tvg-ids
  for (const c of channels) {
    if (c.isEventSlot || !c.tvgId) continue
    const k = `${c.group}|${sorted(c.name)}`
    ;(ids.get(k) ?? ids.set(k, new Set()).get(k)!).add(c.tvgId)
  }
  return listings.flatMap(({ home, listings }) => {
    const idsOf = (name: string) => siteKeys(name).flatMap((k) => [...(ids.get(`${home}|${k}`) ?? [])])
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
const names = (title: string, sides: [string[], string[]]) => {
  const w = teamWords(title)
  return sides.every((side) => side.some((s) => w.some((t) => sameWord(s, t))))
}

/**
 * A channel's programmes ([blocks], one channel's, with its [at] reader and [make] writer) with the
 * listings applied: a match the site gives it and its guide lacks is added over what was there, and a
 * match its guide gives it that the site lists elsewhere, while knowing the channel, is dropped.
 */
export function applyListings(
  id: string,
  blocks: string[],
  placed: Placed[],
  at: (b: string, k: 'start' | 'stop') => number,
  title: (b: string) => string,
  make: (start: number, stop: number, title: string) => string,
  retime: (b: string, start: number, stop: number) => string,
): string[] {
  let out = blocks
  for (const p of placed) {
    const stop = p.start + MATCH_MS
    const during = (b: string) => at(b, 'start') < stop && at(b, 'stop') > p.start
    const shown = out.filter((b) => during(b) && names(title(b), p.sides))
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

const LISTINGS_TTL = 2 * 3600_000
let cache: { at: number; job: Promise<{ home: string; listings: Listing[] }[]> } | undefined

/** Every site's matches, fetched at most every 2 h; a site that fails is left out until the next try. */
export function fetchListings(): Promise<{ home: string; listings: Listing[] }[]> {
  if (!cache || Date.now() - cache.at > LISTINGS_TTL) {
    const job = Promise.all(SITES.map(async (s) => {
      try {
        const res = await fetch(s.url, { headers: { 'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36' }, signal: AbortSignal.timeout(15_000) })
        return { home: s.home, listings: res.ok ? parseListings(await res.text(), s.tz) : [] }
      } catch {
        return { home: s.home, listings: [] }
      }
    }))
    cache = { at: Date.now(), job }
  }
  return cache.job
}
