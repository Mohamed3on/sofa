// Builders for the Fire TV app's own feeds (/api/app/*). They read the same curated
// channels and guide as the TiviMate feeds, so both apps always show the same thing.
import { Database } from 'bun:sqlite'
import { chanKey, COMPS, couldBeLiveMatch, learnedCompetitions, replayAirings, teamWords, saneFps, unxml, type Channel } from './curation.js'
import { VPN_HOST, type BackupConfig, type XtreamConfig } from './provider.js'
import { medianMbps, qualityOf } from './quality.js'

export interface AppChannel {
  id: number
  name: string
  logo: string
  group: string
  tvgId: string
  slot: boolean
  /** The channel's own (non-🏆) group, when it is in the feed; 🏆-only channels have none. */
  home?: string
  /** Feeds of one channel share a family: home group + chanKey. */
  family: string
  /** Position in the family, best feed first (server quality order). */
  rank: number
  /** Probed video height, null when never measured. */
  h: number | null
  /** Name-marker quality tier (qualityScore). */
  q: number
  /** Event slot whose current name reads as a fixture. */
  fixture?: true
  badges: string[]
  /** The feed's bitrate in Mbps, measured while the app played it; absent when never measured. */
  mbps?: number
}

type ProviderRoute = { plain: string; vpn: string; user: string; pass: string }

export interface AppFeed {
  groups: string[]
  route: ProviderRoute
  /** Provider B's fronts and account, when configured; its feeds are the ids from BACKUP_ID up. */
  backup?: ProviderRoute
  /** The Real-Debrid API token (REAL_DEBRID_TOKEN), when set: the stick makes every Real-Debrid call itself. */
  realDebrid?: string
  /** The TorBox API token (TORBOX_TOKEN), when set: the stick uses TorBox instead, every call its own. */
  torbox?: string
  channels: AppChannel[]
}

const isComp = (group: string) => group.startsWith('🏆')

function badges(streamId: number): string[] {
  const m = qualityOf(streamId)
  if (!m) return []
  const out: string[] = []
  if (m.h >= 2160) out.push('4K')
  else if (m.h >= 1080) out.push('FHD')
  const fps = saneFps(m.fps)
  if (fps >= 50) out.push(`${Math.round(fps)}fps`)
  if (m.atmos) out.push('Atmos')
  else if (m.surround) out.push('5.1')
  return out
}

export function buildAppFeed(cfg: XtreamConfig, channels: Channel[], backup?: BackupConfig, realDebrid?: string, torbox?: string): AppFeed {
  const live = channels.filter((c) => !c.vodExt)
  const home = new Map<number, string>()
  for (const c of live) if (!isComp(c.group) && !home.has(c.streamId)) home.set(c.streamId, c.group)
  const familyOf = (c: Channel) => `${home.get(c.streamId) ?? c.group}|${chanKey(c.name)}`
  // Members in server order: home groups first (they are quality-sorted), then 🏆-only channels.
  // A 🏆 copy shares its original's streamId, so it is never counted as a backup.
  const members = new Map<string, number[]>()
  for (const c of [...live.filter((c) => !isComp(c.group)), ...live.filter((c) => isComp(c.group))]) {
    const f = familyOf(c)
    const list = members.get(f) ?? members.set(f, []).get(f)!
    if (!list.includes(c.streamId)) list.push(c.streamId)
  }
  return {
    groups: [...new Set(live.map((c) => c.group))],
    route: { plain: cfg.host, vpn: VPN_HOST, user: cfg.user, pass: cfg.pass },
    ...(backup && { backup: { plain: backup.host, vpn: backup.vpnHost, user: backup.user, pass: backup.pass } }),
    ...(realDebrid && { realDebrid }),
    ...(torbox && { torbox }),
    channels: live.map((c) => {
      const family = familyOf(c)
      return {
        id: c.streamId,
        name: c.name,
        logo: c.logo,
        group: c.group,
        tvgId: c.tvgId,
        slot: c.isEventSlot,
        home: home.get(c.streamId),
        family,
        rank: members.get(family)!.indexOf(c.streamId),
        h: qualityOf(c.streamId)?.h ?? null,
        q: c.q ?? 2,
        ...(c.isEventSlot && couldBeLiveMatch(c.name) ? { fixture: true as const } : {}),
        badges: badges(c.streamId),
        mbps: medianMbps(qualityOf(c.streamId)),
      }
    }),
  }
}

/** XMLTV "20261101120000 +0200" → epoch seconds. */
const xmltvSeconds = (v: string) => {
  const m = v.match(/^(\d{4})(\d\d)(\d\d)(\d\d)(\d\d)(\d\d)\s*([+-]\d\d)(\d\d)/)
  return m ? Date.parse(`${m[1]}-${m[2]}-${m[3]}T${m[4]}:${m[5]}:${m[6]}${m[7]}:${m[8]}`) / 1000 : NaN
}
const ATTR = { start: /\bstart="([^"]*)"/, stop: /\bstop="([^"]*)"/, channel: /\bchannel="([^"]*)"/ }

/** Search form of a title: lowercased, accents stripped ("Bayern München" → "bayern munchen"). */
export const searchNorm = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()

const descrWords = (s: string) => searchNorm(s).match(/[\p{L}\p{N}]+/gu) ?? []
/**
 * A guide description as one paragraph. Some feeds send line breaks as the text "\r\n", and beIN's
 * match rows repeat the row ("Arsenal vs Hull City", "At 7-11-2026, MECCA 18:00, GMT 15:00",
 * "Premier League", "Season 2026/2027", "Emirates Stadium Stadium", "."): a line whose every word the
 * title already says, or that is the fixture or its kick-off time, is dropped, and a doubled last word made single.
 */
export function guideDescr(title: string, raw: string): string {
  const said = new Set([...descrWords(title), 'season', 'at'])
  return raw.replace(/\\[rn]/g, '\n').split(/\s*\n\s*/)
    .filter((l) => !/\bGMT \d|^[^.]+ vs [^.]+$/.test(l) && !descrWords(l).every((w) => said.has(w)))
    .map((l) => l.trim().replace(/(?<!\p{L})(\p{L}+) \1$/u, '$1'))
    .reduce((p, l) => (p ? `${p}${/[.!?:]$/.test(p) ? ' ' : ' · '}${l}` : l), '')
}

/**
 * The app's guide as a gzip SQLite file, parsed from the same XMLTV TiviMate gets so it
 * carries the same corrections. Rows flag name-as-title filler (synthetic) and titles
 * that could be a live match, and name the 🏆 group of the competition a live match
 * belongs to (comp, by the same rules as the 🏆 groups); programmes that ended over 2h
 * ago are dropped. With [crests], a crests table gives each such match title's two crests
 * (home, away, either null), for the app's match rows; an app before it never reads the table.
 */
// Broadcasters' superscript tags ("ᴸᶦᵛᵉ") are letters too, never a team.
const SUPERSCRIPT = /[ᴬ-ᶿ⁰-₟]+/g
const compGroup = new Map(COMPS.map((c) => [c.key, c.group]))

export function buildAppGuide(channels: Channel[], xmltv: string, now = Date.now(), crests?: (title: string) => [string | null, string | null] | null): Uint8Array {
  // Name-as-title filler: ours (slots, uncovered channels) and the provider's own 2-hour blocks
  // named for the channel ("IT: DAZN DIRETTA GOL EUROPA LEAGUE" all day), never a programme.
  const filler = new Set(channels.map((c) => `${c.tvgId}\n${c.name.toLowerCase()}`))
  const db = new Database(':memory:')
  db.run(`CREATE TABLE programmes (tvg_id TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL,
    title TEXT NOT NULL, norm TEXT NOT NULL, descr TEXT NOT NULL, synthetic INTEGER NOT NULL, match INTEGER NOT NULL, comp TEXT)`)
  const insert = db.prepare('INSERT INTO programmes VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)')
  const cutoff = now / 1000 - 2 * 3600
  // Every programme first, the ones already over too: a re-run is told by the airings before it.
  const rows = [...xmltv.matchAll(/<programme\b([^>]*)>([\s\S]*?)<\/programme>/g)].map((m) => {
    const attr = (k: keyof typeof ATTR) => unxml(m[1].match(ATTR[k])?.[1] ?? '')
    const tvgId = attr('channel')
    const title = unxml(m[2].match(/<title\b[^>]*>([\s\S]*?)<\/title>/)?.[1] ?? '').trim()
    const descr = guideDescr(title, unxml(m[2].match(/<desc\b[^>]*>([\s\S]*?)<\/desc>/)?.[1] ?? ''))
    return { tvgId, title, descr, start: xmltvSeconds(attr('start')), stop: xmltvSeconds(attr('stop')), synthetic: filler.has(`${tvgId}\n${title.toLowerCase()}`) }
  })
  const replays = replayAirings(rows.map((r) => (r.synthetic ? { ...r, title: '' } : r)), new Date(now))
  // A re-run is no live match, and no 🏆 group's either (Live now lists those).
  const comps = rows.map((r, i) => (r.synthetic || replays.has(i) ? undefined : learnedCompetitions(r.title)[0]))
  // A match titled without its competition ("AEK Atene - Real Madrid") takes the one other channels
  // name for it: of the tagged airings starting within 90 min, those sharing the most team words, at
  // least two ("aek", "madrid"; a LaLiga re-run's lone "madrid" is not enough), if they agree.
  const words = rows.map((r) => [...new Set(teamWords(r.title.replace(SUPERSCRIPT, '')))])
  const taggedByWord = new Map<string, number[]>()
  comps.forEach((c, j) => c && words[j].forEach((w) => (taggedByWord.get(w) ?? taggedByWord.set(w, []).get(w)!).push(j)))
  rows.forEach((r, i) => {
    if (comps[i] || r.synthetic || replays.has(i) || !(r.stop > cutoff) || !couldBeLiveMatch(r.title)) return
    const shared = new Map<number, number>()
    for (const w of words[i]) for (const j of taggedByWord.get(w) ?? []) if (Math.abs(rows[j].start - r.start) <= 5400) shared.set(j, (shared.get(j) ?? 0) + 1)
    const most = Math.max(0, ...shared.values())
    const found = new Set([...shared].filter(([, n]) => n === most).map(([j]) => comps[j]))
    if (most >= 2 && found.size === 1) comps[i] = [...found][0]
  })
  db.run('CREATE TABLE crests (title TEXT PRIMARY KEY, home TEXT, away TEXT)')
  const crest = db.prepare('INSERT OR IGNORE INTO crests VALUES (?, ?, ?)')
  db.transaction(() => {
    rows.forEach((r, i) => {
      if (!(r.stop > cutoff)) return
      const rerun = replays.has(i)
      const comp = comps[i]
      const match = !rerun && couldBeLiveMatch(r.title)
      insert.run(r.tvgId, r.start, r.stop, r.title, searchNorm(r.title), r.descr,
        r.synthetic ? 1 : 0, match ? 1 : 0, comp ? compGroup.get(comp)! : null)
      const found = comp && match ? crests?.(r.title) : null
      if (found) crest.run(r.title, found[0], found[1])
    })
  })()
  db.run('CREATE INDEX programmes_channel ON programmes (tvg_id, start)')
  db.run('CREATE INDEX programmes_start ON programmes (start)')
  const file = db.serialize()
  db.close()
  return Bun.gzipSync(file)
}
