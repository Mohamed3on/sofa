// Worst-case data for the fixture server (break-ui): the same feed, guide and scores the app reads, bent at that
// boundary so the channel list, Live now and the info view meet the real values that break layouts. Every value is
// taken from a real feed or guide (2026-11-09) or is the plausible next step from one; nothing random.
//   demo   the real feed as fetched
//   worst  the first group's first rows each carry a different real extreme (see ROWS), Live now gets a known match
//          with long names, a missing crest and a stoppage-time clock, plus the slot and Arabic matches
//   empty  no group has rows but one (the feed needs a channel), the guide has no matches on
//   one    one channel, one programme, one match: every count at 1
//   huge   the first group at 2,000 rows, 60 groups
import { Database } from 'bun:sqlite'
import { copyFileSync, readFileSync, rmSync } from 'node:fs'

export const MODES = ['demo', 'worst', 'empty', 'one', 'huge'] as const
export type Mode = (typeof MODES)[number]

type Channel = { id: number; name: string; logo: string; group: string; tvgId: string; family: string; rank: number; badges: string[]; slot?: boolean; fixture?: boolean; home?: string | null }
type Feed = { groups: string[]; channels: Channel[]; compLogos?: Record<string, string> }
type Prog = { title: string; descr?: string; from: number; to: number; match?: boolean; comp?: string }
type Row = { name?: string; badges?: string[]; logo?: string; slot?: boolean; fixture?: boolean; guide?: 'none' | Prog[] }

const MIN = 60
const BROKEN_LOGO = 'http://10.0.2.2:8791/logo/404.png'
const KNOWN = 'UEFA Champions League -- Atlético Madrid vs Bayern' // a real crests title, its home_name as the guide had it
const ARABIC_MATCH = 'الدوري الإنجليزي الممتاز: مانشستر سيتي ضد ليفربول'

/** The first group's first rows, top to bottom; each fails a different way. Times are minutes from now. */
const ROWS: Row[] = [
  { // the longest real event slot name (149 chars), all three badges, a long match title airing now
    name: 'Next | YOG | Halbfinale Dakar 2026 Youth Olympic Games | Jugend (m) | Originalton | all | 09-11-2026 | 16:25 (GMT) | 8K EXCLUSIVE | DE: SOCCER PPV 14',
    slot: true, fixture: true, badges: ['4K', '50fps', 'Atmos'],
    guide: [
      { title: 'Borussia Mönchengladbach vs Wolverhampton Wanderers - Premier League', from: -20, to: 95, match: true, comp: '🏆 Premier League' },
      { title: 'Premier League Highlights: Every Goal From Matchweek 11, Including Stoppage-Time Winners', from: 95, to: 155 },
    ],
  },
  { // Arabic name and guide (RTL inside an LTR row), the longest real badge line
    name: 'AR: بي إن سبورت ١ بريميوم HD', badges: ['FHD', '50fps', '5.1'],
    guide: [
      { title: ARABIC_MATCH, from: -50, to: 65, match: true, comp: '🏆 Premier League' },
      { title: 'AR: يوميات رفيق', from: 65, to: 95 },
    ],
  },
  { // Cyrillic, its logo 404s (monogram), a programme of zero length next (the real guide has 8)
    name: 'RU: МАТЧ! ПРЕМЬЕР HD', logo: BROKEN_LOGO, badges: ['FHD'],
    guide: [
      { title: 'Футбол. Российская Премьер-лига. «Зенит» — «Спартак»', from: -10, to: 50 },
      { title: 'Новости', from: 50, to: 50 },
      { title: 'После футбола с Георгием Черданцевым', from: 50, to: 110 },
    ],
  },
  { name: 'UK: ITV', logo: BROKEN_LOGO, badges: [], guide: 'none' }, // one short word, no logo, no guide
  { // the known match (scores below): long ESPN names, a missing home crest; two more channels show it
    badges: ['4K', '50fps'],
    guide: [{ title: KNOWN, from: -100, to: 15, match: true, comp: '🏆 Champions League' }],
  },
  { name: 'ES: M+ LIGA DE CAMPEONES ʰᵉᵛᶜ', badges: ['FHD', '50fps', '5.1'], guide: [{ title: KNOWN, from: -100, to: 15, match: true, comp: '🏆 Champions League' }] },
  { name: '4K: TNT ULTIMATE ᵁᴴᴰ ³⁸⁴⁰ᴾ+ (LIVE-EVENT)', badges: ['4K', '50fps', 'Atmos'], guide: [{ title: KNOWN, from: -100, to: 15, match: true, comp: '🏆 Champions League' }] },
  { // a programme that overran its slot (progress past the end) and nothing next
    guide: [{ title: 'Tom & Jerry: The Fast and the Furry (2005)', descr: 'Tom &amp; Jerry race across America.', from: -130, to: 1 }],
  },
]

/** The known match's crests and its score: names as long as ESPN gives them, a stoppage-time clock. */
const CRESTS = { title: KNOWN, home: null, away: 'https://a.espncdn.com/combiner/i?img=/i/teamlogos/soccer/500/132.png&w=64&h=64', home_key: 'atletico madrid', away_key: 'bayern munich', home_name: 'UEFA Champions League -- Atlético Madrid', away_name: 'Bayern Munich' }
const SCORES = { 'atletico madrid|bayern munich': { hs: 4, as: 4, state: 'in', clock: "90'+12'" } }

const familiesIn = (feed: Feed, group: string) => [...new Set(feed.channels.filter((c) => c.group === group).map((c) => c.family))]

function writeGuide(src: string, out: string, edit: (db: Database, now: number) => void) {
  rmSync(out, { force: true })
  copyFileSync(src, out)
  const db = new Database(out)
  edit(db, Math.floor(Date.now() / 1000))
  db.close()
  return Bun.gzipSync(readFileSync(out))
}

const insert = (db: Database, tvg: string, now: number, p: Prog) =>
  db.run('INSERT INTO programmes VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)', [tvg, now + p.from * MIN, now + p.to * MIN, p.title, p.title.toLowerCase(), p.descr ?? '', p.match ? 1 : 0, p.comp ?? null])

/** [feed] and [guideDb] (a plain SQLite file) bent to [mode]; returns the feed, the gzipped guide and the scores. */
export function build(mode: Mode, feed: Feed, guideDb: string, work: string): { feed: Feed; guide: Uint8Array; scores: object } {
  const f: Feed = structuredClone(feed)
  const first = f.groups[0]
  const out = `${work}/guide-${mode}.db`
  if (mode === 'demo') return { feed: f, guide: writeGuide(guideDb, out, () => {}), scores: {} }

  if (mode === 'worst') {
    const fams = familiesIn(f, first).slice(0, ROWS.length)
    const tvg = (i: number) => `worst.${i}`
    fams.forEach((fam, i) => {
      const r = ROWS[i]
      for (const c of f.channels) {
        if (c.family !== fam || c.group !== first) continue
        if (r.name) c.name = r.name
        if (r.badges) c.badges = r.badges
        if (r.logo) c.logo = r.logo
        if (r.slot !== undefined) c.slot = r.slot
        if (r.fixture !== undefined) c.fixture = r.fixture
        c.tvgId = r.guide === 'none' ? '' : tvg(i)
      }
    })
    const guide = writeGuide(guideDb, out, (db, now) => {
      ROWS.forEach((r, i) => { if (Array.isArray(r.guide)) r.guide.forEach((p) => insert(db, tvg(i), now, p)) })
      db.run('INSERT OR REPLACE INTO crests VALUES ($title, $home, $away, $home_key, $away_key, $home_name, $away_name)', Object.fromEntries(Object.entries(CRESTS).map(([k, v]) => [`$${k}`, v])))
    })
    return { feed: f, guide, scores: SCORES }
  }

  if (mode === 'empty') {
    const keep = f.channels.find((c) => c.group === f.groups.at(-1))!
    f.channels = [keep]
    return { feed: f, guide: writeGuide(guideDb, out, (db) => db.run('UPDATE programmes SET comp = NULL, "match" = 0')), scores: {} }
  }

  if (mode === 'one') {
    const c = f.channels.find((c) => c.group === first && c.tvgId)!
    f.channels = [{ ...c, tvgId: 'one.0' }]
    f.groups = [first]
    const guide = writeGuide(guideDb, out, (db, now) => {
      db.run('DELETE FROM programmes')
      insert(db, 'one.0', now, { title: 'Newcastle vs Arsenal - Premier League', from: -30, to: 75, match: true, comp: first })
    })
    return { feed: f, guide, scores: {} }
  }

  // huge: 2,000 rows in the first group (copies of real channels, each its own family), 60 groups
  const base = f.channels.filter((c) => c.group !== first)
  const many = Array.from({ length: 2000 }, (_, i) => ({ ...base[i % base.length], id: 9e8 + i, group: first, family: `huge ${i}`, rank: 0 }))
  f.channels = [...many, ...f.channels.filter((c) => c.group !== first)]
  const extra = Array.from({ length: 60 - f.groups.length }, (_, i) => `🏆 ${f.groups[1 + (i % 11)].replace('🏆 ', '')} ${i + 2}`)
  f.groups = [...f.groups, ...extra]
  extra.forEach((g, i) => f.channels.push({ ...base[i], id: 9.5e8 + i, group: g, family: `extra ${i}`, rank: 0 }))
  return { feed: f, guide: writeGuide(guideDb, out, () => {}), scores: {} }
}
