// Learn which channels carry which competition from the EPG. The guides only reach
// ~36h ahead, so one look can't see a cup round or a UEFA week — instead this scans
// our own /api/epg for live-match programmes and accumulates (tvg-id -> competition
// -> last-seen date) into .cache/competitions.json, dropping entries older than
// 60 days. Event slots (ppv.*) are skipped: their name IS the event, so only their
// current name counts (see staticCompetitions). So are name-as-title filler rows.
// Jev judges each match-like title on a football channel once (jevCompetitions; verdicts
// cached in .cache/jev-titles.json); without TYPESAFE_API_KEY, or if Jev fails, the
// regexes decide uncached titles instead.
// Usage: bun scripts/learn-competitions.ts   (PLAYLIST_TOKEN, TYPESAFE_API_KEY from env /
//        .env.local; EPG_ENDPOINT overrides the local server)
import { COMP_SOURCE, couldBeLiveMatch, jevCompetitions, learnedCompetitions, learnedFile } from '../lib/curation.js'
import { jevTokens } from '../lib/jev.js'

const endpoint = process.env.EPG_ENDPOINT ?? 'http://127.0.0.1:8790/api/epg'
const token = process.env.PLAYLIST_TOKEN
const local = endpoint.startsWith('file:')
if (!token && !local) throw new Error('PLAYLIST_TOKEN missing')
// A file: endpoint (an EPG saved locally) needs no token.
const get = async (url: string) => {
  const resp = await fetch(local ? url : `${url}?t=${token}`)
  if (!resp.ok) throw new Error(`${url} -> HTTP ${resp.status}`)
  return resp.text()
}
const xml = await get(endpoint)
// Only football channels feed the 🏆 groups, so only their titles are worth a Jev call.
const football = local ? null : new Set(
  [...(await get(endpoint.replace(/\/epg$/, '/playlist'))).matchAll(/tvg-id="([^"]*)".*group-title="([^"]*)"/g)]
    .filter((m) => COMP_SOURCE.has(m[2])).map((m) => m[1]),
)

const KEEP_DAYS = 60
const cutoff = new Date(Date.now() - KEEP_DAYS * 864e5).toISOString().slice(0, 10)
const next: Record<string, Record<string, string>> = {}
for (const [id, comps] of Object.entries(learnedFile.read(true))) {
  for (const [k, seen] of Object.entries(comps)) if (seen >= cutoff) (next[id] ??= {})[k] = seen
}
const unescape = (s: string) => s.replace(/&apos;/g, "'").replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
const names = new Map([...xml.matchAll(/<channel id="([^"]*)"><display-name>([^<]*)</g)].map((m) => [m[1], unescape(m[2])]))
const programmes = [...xml.matchAll(/<programme start="(\d{4})(\d{2})(\d{2})[^"]*"[^>]*channel="([^"]*)"[^>]*>\s*<title[^>]*>([^<]*)<\/title>/g)]
  .map(([, y, mo, d, id, title]) => ({ id, seen: `${y}-${mo}-${d}`, title: unescape(title) }))
  .filter((p) => !p.id.startsWith('ppv.') && p.title !== names.get(p.id))

const cache = Bun.file(new URL('../.cache/jev-titles.json', import.meta.url))
const verdicts = new Map<string, string[]>((await cache.exists()) ? Object.entries(await cache.json()) : [])
const ask = [...new Set(programmes
  .filter((p) => (!football || football.has(p.id)) && !verdicts.has(p.title) && couldBeLiveMatch(p.title))
  .map((p) => p.title))]
let judged = 0
try {
  if (ask.length) for (const [title, keys] of await jevCompetitions(ask)) verdicts.set(title, keys)
  await Bun.write(cache, JSON.stringify(Object.fromEntries(verdicts)))
  judged = ask.length
} catch (e) {
  console.warn(`Jev unavailable (${e instanceof Error ? e.message : e}); regexes decide ${ask.length} uncached titles`)
}

let hits = 0
for (const { id, seen, title } of programmes) {
  for (const k of verdicts.get(title) ?? learnedCompetitions(title)) {
    const e = (next[id] ??= {})
    if (!e[k] || e[k] < seen) e[k] = seen
    hits++
  }
}
// Sorted keys -> a deterministic file. The server reads it again on its own; no restart.
const sorted = Object.fromEntries(
  Object.keys(next).sort().map((id) => [id, Object.fromEntries(Object.entries(next[id]).sort())]),
)
await learnedFile.write(sorted)
console.log(`${hits} live-match programme hits; ${Object.keys(sorted).length} channels in cache`)
console.log(`Jev: judged ${judged} new titles, ${jevTokens()} input tokens ($${(jevTokens() * 0.042e-6).toFixed(4)})`)
