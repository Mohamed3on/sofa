// Match myepg guide channels to ours where fetchMyepgGuide's name rules find nothing, with Jev,
// into .cache/epg-matches.json, which the server reads again on its own. A channel is unmatched when our /api/epg has only its name-as-title
// filler. Candidates are guide channels with programmes that carry the same channel number and
// a similar name (or list ours as an alias); Jev must pick one (≥0.8) AND confirm it in a
// separate yes/no (≥0.8), since a wrong guide is worse than none. Verdicts are cached in
// .cache/jev-epg.json, so a re-run only asks about channels it hasn't judged yet.
// Run it rarely (after the channel list changes): each run downloads both full guides, and a
// burst of downloads gets the myepg account blocked for ~30 min — production's guide included.
// Run it on the box, as the server's user, so the server sees the result:
//   cd /opt/sofa && runuser -u sofa -- /usr/local/bin/bun scripts/match-epg-channels.ts
// (PLAYLIST_TOKEN, TYPESAFE_API_KEY from .env; API_BASE overrides the local server)
import { chanKey, epgMatchesFile } from '../lib/curation.js'
import { askJev, jevTokens, type JevQuestion } from '../lib/jev.js'

const api = process.env.API_BASE ?? 'http://127.0.0.1:8790/api'
const get = async (url: string) => {
  const resp = await fetch(url, { headers: { 'User-Agent': 'VLC/3.0.18' } })
  if (!resp.ok) throw new Error(`${url.replace(/\?.*/, '')} -> HTTP ${resp.status}`)
  return resp
}
const unescape = (s: string) => s.replace(/&apos;/g, "'").replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')

// Our regular channels (first feed per tvg-id) and which of them the guide leaves as filler.
const ours = new Map<string, { name: string; group: string }>()
const m3u = await (await get(`${api}/playlist?t=${process.env.PLAYLIST_TOKEN}`)).text()
for (const [, id, name, group] of m3u.matchAll(/tvg-id="([^"]*)" tvg-name="([^"]*)" tvg-logo="[^"]*" group-title="([^"]*)"/g)) {
  if (id && !id.startsWith('ppv.') && !group.startsWith('🏆') && !ours.has(id)) ours.set(id, { name, group })
}
const epg = await (await get(`${api}/epg?t=${process.env.PLAYLIST_TOKEN}`)).text()
const shown = new Map([...epg.matchAll(/<channel id="([^"]*)"><display-name>([^<]*)</g)].map((m) => [m[1], unescape(m[2])]))
const real = new Set<string>()
for (const [, id, title] of epg.matchAll(/<programme\b[^>]*\bchannel="([^"]*)"[^>]*>\s*<title[^>]*>([^<]*)</g)) {
  if (unescape(title) !== shown.get(id)) real.add(id)
}
const unmatched = [...ours].filter(([id]) => !real.has(id))

// Guide channels that actually carry programmes.
type Cand = { id: string; names: string[]; key: string; nums: Set<string>; grams: Set<string> }
const NUM: Record<string, string> = { one: '1', two: '2', three: '3', four: '4', five: '5', six: '6', seven: '7', eight: '8', nine: '9', ten: '10' }
// Name words without delivery tags ("(KABEL)", "(WEB 1080)") or feed/quality words; number words as digits.
const words = (s: string) => chanKey(s.replace(/\([^)]*\)?/g, ' ')).split(' ').filter(Boolean).map((w) => NUM[w] ?? w.replace(/^0+(?=\d)/, ''))
const trigrams = (ws: string[]) => { const s = ` ${ws.join('')} `, g = new Set<string>(); for (let i = 0; i < s.length - 2; i++) g.add(s.slice(i, i + 3)); return g }
const cands: Cand[] = []
for (const url of (process.env.EPG_URLS ?? '').split(',').map((s) => s.trim()).filter(Boolean)) {
  const xml = new TextDecoder().decode(Bun.gunzipSync(new Uint8Array(await (await get(url)).arrayBuffer())))
  const live = new Set([...xml.matchAll(/<programme\b[^>]*\bchannel="([^"]*)"/g)].map((m) => m[1]))
  for (const [, id, body] of xml.slice(0, xml.indexOf('<programme')).matchAll(/<channel\b[^>]*\bid="([^"]*)"[^>]*>([\s\S]*?)<\/channel>/g)) {
    if (!live.has(id) || id.startsWith('dummy-')) continue
    const names = [...body.matchAll(/<display-name[^>]*>([^<]*)</g)].map((d) => unescape(d[1]))
    const ws = words(names[0] ?? id)
    cands.push({ id, names, key: names.map((n) => words(n).join(' ')).join('|'), nums: new Set(ws.filter((w) => /^\d+$/.test(w))), grams: trigrams(ws) })
  }
}
// Guide ids end in a country (".de"); where our group is one country, the guide channel must be too.
const COUNTRIES: Record<string, string[]> = {
  '🏴 Sky Sports & UK Sports': ['uk', 'ie'], '🇩🇪 German Sports': ['de', 'at', 'ch'], '🇪🇸 Spanish Sports': ['es'],
  '🇮🇹 Italian Sports': ['it'], '🇫🇷 French Sports': ['fr'], '🇷🇺 Russian Channels': ['ru'],
}
// Shortlist: same channel numbers (and country), then guide channels listing our name as an
// alias, then the closest names by letter trigrams.
const shortlist = (name: string, group: string) => {
  const ws = words(name), nums = ws.filter((w) => /^\d+$/.test(w)), g = trigrams(ws), key = ws.join(' ')
  const sim = (c: Cand) => { let n = 0; for (const x of g) if (c.grams.has(x)) n++; return n / (g.size + c.grams.size - n) }
  const countries = COUNTRIES[group]
  return cands
    .filter((c) => !countries || countries.includes(c.id.match(/\.([a-z]{2,3})$/i)?.[1].toLowerCase() ?? ''))
    .filter((c) => c.nums.size === nums.length && nums.every((n) => c.nums.has(n)))
    .map((c) => ({ c, s: c.key.split('|').includes(key) ? 2 : sim(c) }))
    .filter((x) => x.s >= 0.3).sort((a, b) => b.s - a.s).slice(0, 6).map((x) => x.c)
}

const RULE = 'Delivery and quality details do not matter: tags in parentheses such as (MOBIL), (KABEL), (SAT), (WEB 1080), and 4K, UHD, HD, RAW, HEVC, ⚽ spellings, or feed prefixes like "NM:", "8K:", "VIP:". Anything else in the name does: a different channel number, a different country or language feed, or extra words such as EVENT, WEB 3, EXTRA, XTRA, TOD, ASIA, PREMIUM, GOLF, NEWS make it a different channel.'
// Per channel: the shortlist Jev judged, and the guide channel it matched ('' for none).
type Verdict = { judged: string; guide: string }
const cache = Bun.file(new URL('../.cache/jev-epg.json', import.meta.url))
const verdicts: Record<string, Verdict> = (await cache.exists()) ? await cache.json() : {}
const todo = unmatched.map(([id, ch]) => ({ id, ...ch, list: shortlist(ch.name, ch.group) }))
  .filter((r) => r.list.length && verdicts[r.id]?.judged !== r.list.map((c) => c.id).join(','))
const questions: Record<string, JevQuestion> = {}
todo.forEach((r, i) => {
  const ch = `the IPTV channel ${JSON.stringify(r.name)} (playlist group ${JSON.stringify(r.group)})`
  const label = (c: Cand) => `${c.names.slice(0, 4).join(' / ')} (guide id ${c.id})`
  questions[`m${i}`] = {
    type: 'choice',
    instructions: `Which TV guide channel is exactly the same TV channel as ${ch}? ${RULE}`,
    criteria: { ...Object.fromEntries(r.list.map((c, j) => [`c${j}`, label(c)])), none: 'None of these is exactly this channel' },
  }
  r.list.forEach((c, j) => {
    questions[`n${i}_${j}`] = { type: 'noul', instructions: `Is ${ch} exactly the same TV channel as the TV guide channel ${JSON.stringify(label(c))}? ${RULE}` }
  })
})
const a = todo.length ? await askJev('Matching IPTV playlist channels to TV guide (EPG) channels.', questions) : {}
todo.forEach((r, i) => {
  const { choice = 'none', probabilities = {} } = a[`m${i}`]
  const j = Number(choice.slice(1))
  const ok = choice !== 'none' && probabilities[choice] >= 0.8 && a[`n${i}_${j}`].noul! >= 0.8
  verdicts[r.id] = { judged: r.list.map((c) => c.id).join(','), guide: ok ? r.list[j].id : '' }
})
await Bun.write(cache, JSON.stringify(verdicts))

// Every accepted verdict for a channel still in the playlist (matched ones stop showing as unmatched).
const matches: Record<string, string[]> = {}
for (const [id, { guide }] of Object.entries(verdicts)) if (guide && ours.has(id)) (matches[guide] ??= []).push(id)
const sorted = Object.fromEntries(Object.keys(matches).sort().map((g) => [g, matches[g].sort()]))
await epgMatchesFile.update(() => sorted)
for (const [g, ids] of Object.entries(sorted)) for (const id of ids) console.log(`${ours.get(id)!.name}  →  ${g}`)
console.log(`${unmatched.length} unmatched channels; asked Jev about ${todo.length}; ${Object.values(sorted).flat().length} matched`)
console.log(`Jev: ${jevTokens()} input tokens ($${(jevTokens() * 0.042e-6).toFixed(4)})`)
