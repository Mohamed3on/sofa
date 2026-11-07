// ArabP2P (arabp2p.net), a private Arabic tracker, as a third torrent source for films Torrentio barely covers:
// Arabic-dubbed animated films (its "مدبلج" categories; Egyptian dubs first) and Arabic films (its Arabic-film
// listing, looked up by TMDB id), and the episodes of Arabic-dubbed animated shows (their season packs). Only copies TorBox already holds are offered, so nothing is downloaded from
// the tracker's peers and nothing has to seed.
//
// The tracker is touchy, so everything here is slow and kept:
// - one request at a time, GAP_MS apart; an HTTP refusal or a network failure pauses it for an hour (it bans
//   bursts by IP);
// - a film's search is kept 3 days (a day when it found nothing), a torrent's hash forever;
// - a hash comes from the detail page's magnet when it has one (free), else from the .torrent, which counts
//   against the account's downloads per 24 h (ARABP2P_DAILY, 10 for a plain member). Dubs may use all of
//   them; Arabic films only while DUB_RESERVE are left, and at most PER_FILM per film.
// Nothing runs without ARABP2P_USER and ARABP2P_PASS, which never leave this file.
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import type { RdCopy } from './copies.js'
import { writeAtomic } from './fs.js'
import { getJson } from './http.js'
import { parseRelease } from './release-name.js'
import { tmdbGet, type TmdbTitle } from './tmdb.js'

const BASE = 'https://www.arabp2p.net/'
const UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36'
const GAP_MS = Number(process.env.ARABP2P_GAP_MS ?? 3000)
const PAUSE_MS = 3600_000
const SEARCH_MS = 3 * 864e5
const EMPTY_MS = 864e5
const DAY_MS = 864e5
const PER_FILM = 3
const DUB_RESERVE = 6
const TORBOX_MS = 2 * 3600_000

/** A torrent as a listing shows it. */
export interface Row { id: string; title: string; size: number; seeds: number; dub: boolean; egyptian: boolean; link: string }
/** What a torrent holds: its hash and the video file to play (index in the torrent's file list). */
interface Held { hash: string; file: number; filename: string; size: number }
/** What a show's torrent holds: its hash and each episode's file ("1:5" → index, name, bytes). */
interface Pack { hash: string; eps: Record<string, [number, string, number]> }
interface Store {
  films: Record<string, { at: number; ar: string; rows: Row[] }>
  /** By torrent id; `none` when it holds no single film (a pack, or no video). */
  torrents: Record<string, Held | { none: true }>
  /** When each .torrent was downloaded, for the daily allowance. */
  downloads: number[]
  /** The tracker said the allowance is spent: no downloads before this. */
  fullUntil?: number
  /** The dubbed-films category as crawled: torrent id → the TMDB film it is (0: none found), and when it was last crawled. */
  dubs?: Record<string, number>
  crawled?: number
  /** When TorBox last said it holds a hash. */
  tb?: Record<string, number>
  /** Torrents whose detail page shows no magnet: only their .torrent gives the hash. */
  bare?: Record<string, 1>
  /** The dubbed films' TMDB vote counts: the best-known are hashed first, as TorBox most likely holds them. */
  votes?: Record<string, number>
  /** Dubbed shows' rows by TMDB id, like films' (with the names their files go by); their torrents by id, `none` when no file is one of the show's episodes. */
  shows?: Record<string, { at: number; ar: string; rows: Row[]; names?: string[] }>
  /** The dubbed-shows category as crawled: torrent id → the TMDB show it is (0: none found), and when it was last crawled. */
  showDubs?: Record<string, number>
  showsCrawled?: number
  packs?: Record<string, Pack | { none: true }>
}
const storeFile = () => process.env.ARABP2P_CACHE_FILE ?? fileURLToPath(new URL('../.cache/arabp2p.json', import.meta.url))
// One copy in memory (only the server writes the file; the library build only reads it), saved whole after each change.
let held: Promise<Store> | null = null
const db = () => (held ??= readStore())
const readStore = (file = storeFile()) => readFile(file, 'utf8').then((s) => JSON.parse(s) as Store, (): Store => ({ films: {}, torrents: {}, downloads: [] }))
const save = async () => writeAtomic(storeFile(), await db())

const UNIT: Record<string, number> = { KB: 2 ** 10, MB: 2 ** 20, GB: 2 ** 30, TB: 2 ** 40 }
const bytes = (s: string) => {
  const m = s.match(/([\d.]+)\s*([KMGT]B)/i)
  return m ? Math.round(Number(m[1]) * UNIT[m[2].toUpperCase()]) : 0
}
const digits = (s: string) => s.replace(/[٠-٩]/g, (d) => String('٠١٢٣٤٥٦٧٨٩'.indexOf(d)))
const decode = (s: string) => s.replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#0?39;/g, "'").replace(/&nbsp;/g, ' ')
const text = (html: string) => decode(html.replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim()
const EGYPTIAN = /مصري|المصريه|المصرية|egyptian/i
const DUBBED = /مدبلج|دبلجة|dubbed/i
/** Collections ("باقة", "Complete Edition") and anything over 80 GB hold many films. */
const PACK = /باقة|باقه|مجموعة|collection|complete/i

// --- Pages -------------------------------------------------------------------------------------------------

/** The rows of an Arabic-film title page (`movies-listing&A=`): title, size, seeds, download link. */
export function listingRows(html: string): Row[] {
  return [...html.matchAll(/<tr class="torrent">([\s\S]*?)<\/tr>/g)].flatMap(([, tr]) => {
    const id = tr.match(/torrent-details&(?:amp;)?id=(\d+)/)?.[1]
    const link = tr.match(/href="(download\.php\?[^"]+)"/)?.[1]
    if (!id || !link) return []
    const cells = [...tr.matchAll(/<td[^>]*>([\s\S]*?)<\/td>/g)].map((m) => text(m[1]))
    const title = text(tr.match(/torrent-details[^>]*>([^<]*)</)?.[1] ?? '')
    return [{ id, title, size: bytes(cells[3] ?? ''), seeds: Number(digits(cells[4] ?? '')) || 0, dub: DUBBED.test(title), egyptian: EGYPTIAN.test(title), link: decode(link) }]
  })
}

/** The rows of a text search (`page=torrents&search=`), with their categories read for the dub. */
export function searchRows(html: string): Row[] {
  return html.split('<div class="file-item">').slice(1).flatMap((item) => {
    const id = item.match(/torrent-details&amp;id=(\d+)/)?.[1]
    const link = item.match(/href="(download\.php\?[^"]+)"/)?.[1]
    if (!id || !link) return []
    // A free download's title is a "tor_free_link".
    const title = text(item.match(/class="tor_(?:free_)?link">([^<]*)/)?.[1] ?? '')
    const category = text(item.match(/<span class="category">([\s\S]*?)<\/span>/)?.[1] ?? '')
    return [{
      id, title, size: bytes(item.match(/<span class="size">([^<]*)/)?.[1] ?? ''),
      seeds: Number(digits(item.match(/title="Seeders">[^<]*<a[^>]*>([^<]*)/)?.[1] ?? '')) || 0,
      dub: DUBBED.test(`${title} ${category}`), egyptian: EGYPTIAN.test(title), link: decode(link),
    }]
  })
}

/** A detail page's magnet hash (only some uploads show one) and its file list, in torrent order. */
export function detail(html: string): { hash?: string; files: { name: string; size: number }[] } {
  const files = [...(html.match(/id="files">([\s\S]*?)<\/table>/)?.[1] ?? '').matchAll(/<tr class="torrent">\s*<td>([^<]*)<\/td>\s*<td>([^<]*)<\/td>/g)]
    .map((m) => ({ name: decode(m[1].trim()), size: bytes(m[2]) }))
  return { hash: html.match(/magnet:\?xt=urn:btih:([0-9a-f]{40})/i)?.[1]?.toLowerCase(), files }
}

type B = number | Uint8Array | B[] | { [k: string]: B }
/** A .torrent's info hash (SHA-1 of its bencoded info dictionary) and its files in order; null when it isn't one. */
export function readTorrent(buf: Uint8Array): { hash: string; files: { name: string; size: number }[] } | null {
  let i = 0
  let info: [number, number] | null = null
  const str = (a: number, b: number) => new TextDecoder().decode(buf.subarray(a, b))
  const next = (depth: number): B => {
    const c = buf[i]
    if (c === 0x69) { const e = buf.indexOf(0x65, i); const n = Number(str(i + 1, e)); i = e + 1; return n }
    if (c === 0x6c) { i++; const l: B[] = []; while (buf[i] !== 0x65) l.push(next(depth + 1)); i++; return l }
    if (c === 0x64) {
      i++
      const d: Record<string, B> = {}
      while (buf[i] !== 0x65) {
        const k = str(...span())
        const start = i
        d[k] = next(depth + 1)
        if (depth === 0 && k === 'info') info = [start, i]
      }
      i++
      return d
    }
    const [a, b] = span()
    return buf.subarray(a, b)
  }
  const span = (): [number, number] => {
    const colon = buf.indexOf(0x3a, i)
    const len = Number(str(i, colon))
    if (colon < 0 || !Number.isInteger(len) || len < 0) throw new Error('not bencode')
    i = colon + 1 + len
    return [colon + 1, i]
  }
  if (buf[0] !== 0x64) return null
  try {
    const t = next(0) as Record<string, B>
    const d = t.info as Record<string, B> | undefined
    if (!d || !info) return null
    const name = (v: B | undefined) => (v instanceof Uint8Array ? new TextDecoder().decode(v) : '')
    const files = Array.isArray(d.files)
      ? (d.files as Record<string, B>[]).map((f) => ({ name: name((f.path as B[]).at(-1)), size: Number(f.length) }))
      : [{ name: name(d.name), size: Number(d.length) }]
    return { hash: createHash('sha1').update(buf.subarray(...(info as [number, number]))).digest('hex'), files }
  } catch {
    return null
  }
}

const VIDEO = /\.(mkv|mp4|avi|m4v|ts|mov|wmv)$/i

/**
 * The season and episode a show's file is ("Bluey.S01E05.1080p.mkv", "تيمون وبومبا الجزء 3 الحلقة 5.mp4"), when
 * what comes before is one of the show's [names] (a year and brackets aside) or nothing: a spin-off's file
 * ("Bluey.Compilations.S01E01") is no episode of the show.
 */
export function episodeOf(file: string, names: string[]): [number, number] | null {
  const base = digits(file.split('/').pop() ?? '')
  if (!VIDEO.test(base)) return null
  const m = base.match(/^(.*?)(?<![a-z])s(\d{1,2})[ ._-]?e(\d{1,3})(?!\d)/i) ?? base.match(/^(.*?)(?:الموسم|الجزء)\s*(\d{1,2})\D*?الحلقة\s*(\d{1,3})(?!\d)/)
  if (!m) return null
  const before = norm(m[1].replace(/\[[^\]]*\]|\([^)]*\)/g, ' ').replace(/(?<!\d)(?:19|20)\d\d(?!\d)/g, ' '))
  return !before || names.some((n) => norm(n) === before) ? [Number(m[2]), Number(m[3])] : null
}

/** The episodes in a show's torrent: each one's biggest file. */
export function packEpisodes(files: { name: string; size: number }[], names: string[]): Pack['eps'] {
  const eps: Pack['eps'] = {}
  files.forEach((f, i) => {
    const ep = episodeOf(f.name, names)
    const key = ep && `${ep[0]}:${ep[1]}`
    if (key && !(eps[key]?.[2] >= f.size)) eps[key] = [i, f.name.split('/').pop()!, f.size]
  })
  return eps
}

/** The one video a torrent holds for a film; null for a pack (several big videos) or none. */
export function filmFile(files: { name: string; size: number }[]): { file: number; filename: string; size: number } | null {
  const videos = files.map((f, file) => ({ ...f, file })).filter((f) => VIDEO.test(f.name))
  const big = videos.reduce((a, f) => (f.size > a.size ? f : a), { name: '', size: 0, file: -1 })
  if (big.file < 0 || videos.filter((f) => f.size > big.size / 3).length > 1) return null
  return { file: big.file, filename: big.name, size: big.size }
}

// --- Which rows are the film -------------------------------------------------------------------------------

const norm = (s: string) => digits(s).toLowerCase().normalize('NFKD').replace(/[ً-ٟـ]/g, '')
  .replace(/[أإآ]/g, 'ا').replace(/ة/g, 'ه').replace(/ى/g, 'ي').replace(/[^\p{L}\p{N}]+/gu, ' ').trim()

/**
 * Whether a search row is the film: one of its names as whole words, not followed by another number (so
 * "Toy Story" doesn't take "Toy Story 5"), and any year in the row within one of the film's.
 */
export function isFilm(row: string, names: string[], year: string): boolean {
  const t = ` ${norm(row)} `
  const years = [...digits(row).matchAll(/(?<!\d)(19\d\d|20\d\d)(?!\d)/g)].map((m) => Number(m[1]))
  if (year && years.length && !years.some((y) => Math.abs(y - Number(year)) <= 1)) return false
  return names.map(norm).filter(Boolean).some((n) => {
    let at = t.indexOf(` ${n} `)
    while (at >= 0) {
      const after = t.slice(at + n.length + 2).match(/^(\d+)\b/)?.[1]
      if (!after || (after.length === 4 && Math.abs(Number(after) - Number(year)) <= 1)) return true
      at = t.indexOf(` ${n} `, at + 1)
    }
    return false
  })
}

// --- The tracker -------------------------------------------------------------------------------------------

const jar = new Map<string, string>()
let turn = Promise.resolve()
let last = 0
let pausedUntil = 0
let loggedIn = false

class Paused extends Error {}
function pause(why: string) {
  pausedUntil = Date.now() + PAUSE_MS
  console.warn(`arabp2p: paused an hour (${why})`)
  return new Paused(why)
}

/** One request, in turn and GAP_MS after the last; redirects followed by hand so cookies are kept. */
function request(path: string, init: RequestInit = {}): Promise<Response> {
  const run = turn.then(async () => {
    if (Date.now() < pausedUntil) throw new Paused('paused')
    const wait = last + GAP_MS - Date.now()
    if (wait > 0) await Bun.sleep(wait)
    let url = BASE + path
    for (let hop = 0; ; hop++) {
      last = Date.now()
      let r: Response
      try {
        r = await fetch(url, { ...init, redirect: 'manual', signal: AbortSignal.timeout(20_000), headers: { 'User-Agent': UA, cookie: [...jar].map(([k, v]) => `${k}=${v}`).join('; '), ...(init.headers as Record<string, string>) } })
      } catch (e) {
        throw pause(e instanceof Error ? e.name : 'network')
      }
      for (const c of r.headers.getSetCookie()) {
        const [pair] = c.split(';')
        const eq = pair.indexOf('=')
        if (eq > 0) jar.set(pair.slice(0, eq), pair.slice(eq + 1))
      }
      const to = r.headers.get('location')
      if (r.status >= 300 && r.status < 400 && to && hop < 5) {
        url = new URL(to, url).toString()
        init = { headers: init.headers }
        continue
      }
      // download.php answers 429 once the day's downloads are spent: that's the allowance, read by resolve.
      if (r.status >= 400 && r.status !== 404 && !(r.status === 429 && path.startsWith('download.php'))) throw pause(`HTTP ${r.status} at ${path.split(/[?&]/).slice(0, 2).join(' ')}`)
      return r
    }
  })
  turn = run.then(() => {}, () => {})
  return run
}

const signedIn = (html: string) => /logout\.php\?t=/.test(html)
async function login() {
  await request('index.php')
  await request('index.php?page=login', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded', origin: BASE.slice(0, -1), referer: BASE + 'index.php' },
    body: new URLSearchParams({ uid: process.env.ARABP2P_USER ?? '', pwd: process.env.ARABP2P_PASS ?? '' }).toString(),
  })
  loggedIn = true
}
/** A signed-in page; signs in (once) when the session is gone. */
async function page(path: string): Promise<string> {
  for (let attempt = 0; ; attempt++) {
    if (!loggedIn) await login()
    const html = await (await request(path)).text()
    if (signedIn(html)) return html
    loggedIn = false
    if (attempt) throw pause('sign-in failed')
  }
}

/** Downloads left in the 24 h window; Arabic films leave DUB_RESERVE of them for dubs. */
export function allowance(s: Pick<Store, 'downloads' | 'fullUntil'>, dub: boolean, now = Date.now(), daily = Number(process.env.ARABP2P_DAILY ?? 10)): number {
  if (now < (s.fullUntil ?? 0)) return 0
  const used = s.downloads.filter((t) => now - t < DAY_MS).length
  return Math.max(0, daily - used - (dub ? 0 : DUB_RESERVE))
}

/** A torrent's hash and film file: the detail page's magnet if it shows one, else its .torrent (when the allowance has room beyond [keep]). */
async function resolve(row: Row, dub: boolean, keep = 0): Promise<void> {
  const held = await fetchHash(row, dub, keep)
  if (!held) return
  const s = await db()
  const f = filmFile(held.files)
  s.torrents[row.id] = f ? { hash: held.hash, ...f } : { none: true }
  await save()
}

/** A torrent's hash and files: the detail page's magnet if it shows one, else its .torrent (when the allowance has room beyond [keep]); null without. */
async function fetchHash(row: Row, dub: boolean, keep: number): Promise<{ hash: string; files: { name: string; size: number }[] } | null> {
  const s = await db()
  const d = s.bare?.[row.id] ? { files: [] } : detail(await page(`index.php?page=torrent-details&id=${row.id}`))
  let held: { hash: string; files: { name: string; size: number }[] } | null = d.hash ? { hash: d.hash, files: d.files } : null
  if (!held) {
    if (!s.bare?.[row.id]) {
      ;(s.bare ??= {})[row.id] = 1
      await save()
    }
    if (allowance(s, dub) <= keep) return null
    const r = await request(row.link, { headers: { referer: `${BASE}index.php?page=torrent-details&id=${row.id}` } })
    const buf = new Uint8Array(await r.arrayBuffer())
    held = readTorrent(buf)
    if (!held) {
      // Spent (a 429, or the page saying so): no downloads until the window's first one is a day old; when
      // the box made none of them, try again in 6 h (a refused try costs nothing).
      if (r.status === 429 || /تجاوزت الحد المسموح/.test(new TextDecoder().decode(buf))) {
        const mine = s.downloads.filter((t) => Date.now() - t < DAY_MS)
        s.fullUntil = mine.length ? Math.min(...mine) + DAY_MS : Date.now() + 6 * 3600_000
      } else loggedIn = false
      await save()
      return null
    }
    s.downloads = [...s.downloads.filter((t) => Date.now() - t < DAY_MS), Date.now()]
    await save()
  }
  return held
}

/**
 * A film's rows: an Arabic film's by its TMDB id in the Arabic-film listing, else (its page there has no TMDB
 * id, or it's a dub) by its names, Arabic first, among all uploads (a dub: among the dubbed ones).
 */
async function search(tmdb: string, t: TmdbTitle, arabic: boolean, ar: string): Promise<Row[]> {
  if (arabic) {
    const found = await page(`index.php?page=movies-listing&search=${tmdb}&search_radio=TMDB_id`)
    const pages = [...new Set([...found.matchAll(/page=movies-listing&(?:amp;)?A=(\d+)/g)].map((m) => m[1]))].slice(0, 2)
    const rows: Row[] = []
    for (const a of pages) rows.push(...listingRows(await page(`index.php?page=movies-listing&A=${a}`)))
    if (rows.length) return rows
  }
  // An Arabic film's own title is Arabic; a dub's Arabic title is TMDB's translation.
  const names = [...new Set([arabic ? t.originalTitle : ar, t.name, t.originalTitle].filter(Boolean))]
  const rows = new Map<string, Row>()
  for (const q of names.slice(0, 2)) {
    const html = await page(`index.php?page=torrents&search=${encodeURIComponent(q)}&category=0&active=0&internel=0&order=3&by=2`)
    for (const r of searchRows(html)) if ((arabic || r.dub) && isFilm(r.title, names, t.year)) rows.set(r.id, r)
  }
  return [...rows.values()]
}

/** Rows worth a hash, best first: no packs (by name, size, or a torrent already found to be one); Egyptian dubs, then resolution, then seeds. */
export function wanted(rows: Row[], torrents: Store['torrents'] = {}): Row[] {
  const res = (r: Row) => parseRelease(r.title).res
  return rows
    .filter((r) => !PACK.test(r.title) && r.size < 80 * 2 ** 30 && !(torrents[r.id] && 'none' in torrents[r.id]))
    .sort((a, b) => Number(b.egyptian) - Number(a.egyptian) || res(b) - res(a) || b.seeds - a.seeds)
    .slice(0, PER_FILM)
}

const stale = (f: Store['films'][string] | undefined) => !f || Date.now() - f.at > (f.rows.length ? SEARCH_MS : EMPTY_MS)
/** Whether a lookup would do anything: a search due, or a wanted torrent whose hash could be had now. */
const due = (s: Store, tmdb: string, dub: boolean) =>
  stale(s.films[tmdb]) || wanted(s.films[tmdb].rows, s.torrents).some((r) => !s.torrents[r.id] && (!s.bare?.[r.id] || allowance(s, dub) > 0))

const jobs = new Map<string, Promise<void>>()
/** Brings a film's rows and hashes up to date, in the background; one job per film. */
function refresh(tmdb: string, t: TmdbTitle, arabic: boolean): Promise<void> {
  let job = jobs.get(tmdb)
  if (!job) {
    job = (async () => {
      const tried = new Set<string>()
      const s = await db()
      let f = s.films[tmdb]
      if (stale(f)) {
        const ar = f?.ar || (arabic ? '' : await tmdbGet<{ title?: string }>(`movie/${tmdb}?language=ar`).then((r) => r.title ?? '', () => ''))
        // Rows the dub crawl added are kept alongside what the search finds.
        const found = await search(tmdb, t, arabic, ar)
        f = s.films[tmdb] = { at: Date.now(), ar, rows: [...found, ...(f?.rows ?? []).filter((r) => !found.some((x) => x.id === r.id))] }
        await save()
      }
      // A pack found on the way drops out, so the next row gets its turn.
      for (let next = wanted(f!.rows, s.torrents).find((r) => !s.torrents[r.id] && !tried.has(r.id)); next; next = wanted(f!.rows, s.torrents).find((r) => !s.torrents[r.id] && !tried.has(r.id))) {
        tried.add(next.id)
        await resolve(next, !arabic)
      }
    })()
      .catch((e) => void (e instanceof Paused || console.warn(`arabp2p: ${tmdb} failed (${e instanceof Error ? e.message : e})`)))
      .finally(() => jobs.delete(tmdb))
    jobs.set(tmdb, job)
  }
  return job
}

const tbSeen = new Map<string, { at: number; held: boolean }>()
/**
 * Which of [hashes] TorBox holds, each answer kept 2 h (and a held one noted in [s] for the library's row); all
 * of them when there's no TorBox key, none when TorBox fails.
 */
async function onTorBox(hashes: string[], s: Store): Promise<Set<string>> {
  const key = process.env.TORBOX_TOKEN
  if (!key) return new Set(hashes)
  const ask = hashes.filter((h) => !(Date.now() - (tbSeen.get(h)?.at ?? 0) < TORBOX_MS))
  for (let i = 0; i < ask.length; i += 50) {
    const batch = ask.slice(i, i + 50)
    const r = await getJson<{ data?: { hash: string }[] }>(`https://api.torbox.app/v1/api/torrents/checkcached?hash=${batch.join(',')}&format=list&list_files=false`, { headers: { Authorization: `Bearer ${key}` }, timeoutMs: 3000, retries: 0 }).catch(() => null)
    if (!r) continue
    const held = new Set((r.data ?? []).map((d) => d.hash.toLowerCase()))
    for (const h of batch) {
      tbSeen.set(h, { at: Date.now(), held: held.has(h) })
      if (held.has(h)) (s.tb ??= {})[h] = Date.now()
    }
    // Kept on disk for the library build, which reads the file.
    if (held.size) await save()
  }
  // One answer without a hash TorBox held in the last day doesn't drop it: its answers can leave one out, and the
  // stick asks TorBox itself before playing.
  return new Set(hashes.filter((h) => tbSeen.get(h)?.held || Date.now() - (s.tb?.[h] ?? 0) < DAY_MS))
}

// --- The dubbed films, crawled daily -----------------------------------------------------------------------

/** The tracker's category of Arabic-dubbed animated films (under "انمي مدبلج عربي"), newest first, 30 a page. */
const DUBBED_FILMS = 98
const FIRST_PAGES = 20
const DAILY_PAGES = 2
/** Downloads the crawl leaves for films asked for. */
const KEEP_FOR_ASKED = 2
const NOT_NAME = /^(?:فيلم|مدبلج\S*|دبلج\S*|دبلجتين|نسخة|نسخه|صوت|ترجمة|مترجم|عربي|عربية|بالعامية|العامية|المصرية|مصري|مصرية|فصحى|بالفصحى|الإنجليزية|انجليزي|و|\+)$/
const TAG = /(?<![\p{L}\d])(?:2160p|1440p|1080p|720p|576p|480p|4k|2k|hd|fhd|uhd|blu-?ray|bd|web|web-?dl|webrip|dsnp|nf|amzn|osn|shahid|hdtv|dvd|dual|x26[45]|h ?26[45]|hevc|ddp?5|atmos|dv|mkv|remux)(?![\p{L}\d])/iu

/**
 * What to ask TMDB for an upload's film: its year, and its names from the part before the first tag or year,
 * brackets dropped: the English ones first, then the Arabic ones, filler words ("مدبلج", "فيلم") left out.
 */
export function uploadNames(title: string): { names: string[]; year: string } {
  const t = digits(decode(title))
  const year = t.match(/(?<!\d)(19\d\d|20\d\d)(?!\d)/)?.[1] ?? ''
  const plain = t.replace(/\[[^\]]*\]|\([^)]*\)/g, ' ')
  const head = plain.slice(0, Math.min(...[plain.search(TAG), plain.search(/(?<!\d)(?:19|20)\d\d(?!\d)/)].map((i) => (i < 0 ? plain.length : i))))
  const parts = head.split(/\s[—–|-]\s|[—–|]/).map((p) => p.trim()).filter(Boolean)
  const latin = parts.flatMap((p) => p.match(/[A-Za-z][\w'’&:!.,-]*(?:\s+[\w'’&:!.,-]+)*/g) ?? []).map((s) => s.replace(/\bAKA\b.*$/i, '').trim())
  const arabic = parts.map((p) => p.replace(/[A-Za-z][\w'’&:!.,-]*/g, ' ').split(/\s+/).filter((w) => w && !NOT_NAME.test(w)).join(' '))
  const names = [...latin.filter((s) => s.length > 1).sort((a, b) => b.length - a.length), ...arabic.filter((s) => /\p{Script=Arabic}/u.test(s))]
  return { names: [...new Set(names)], year }
}

/** Of TMDB's answers for [q], the animated film released within a year of [year]: the one titled [q] exactly, else (for a name of two words or more) the first. */
export function pickFilm(results: { id: number; title?: string; original_title?: string; release_date?: string; genre_ids?: number[] }[], q: string, year: string): number {
  const fits = results.filter((x) => x.genre_ids?.includes(16) && (!year || Math.abs(Number(x.release_date?.slice(0, 4)) - Number(year)) <= 1))
  return (fits.find((x) => [x.title, x.original_title].some((n) => n && norm(n) === norm(q))) ?? (norm(q).includes(' ') ? fits[0] : undefined))?.id ?? 0
}

/** The TMDB animated film an upload is: the first of its names TMDB finds one for; 0 none. */
async function tmdbFilm(title: string): Promise<number> {
  const { names, year } = uploadNames(title)
  for (const q of names.slice(0, 3)) {
    const r = await tmdbGet<{ results: Parameters<typeof pickFilm>[0] }>(`search/movie?query=${encodeURIComponent(q)}${year ? `&year=${year}` : ''}`).catch(() => null)
    const id = r ? pickFilm(r.results, q, year) : 0
    if (id) return id
  }
  return 0
}

let crawling: Promise<void> | null = null
// Extra crawls (for the allowance) are 30 min apart at least, so a failing download can't repeat on every ask.
let crawlStarted = 0
/**
 * Reads the dubbed-films and dubbed-shows categories (FIRST_PAGES the first time, then the newest pages until a known
 * upload), files each new upload under its TMDB film or show, gives each dubbed film's best copy and each dubbed show's
 * best pack a hash (the best-known first, films and shows in turn, leaving KEEP_FOR_ASKED downloads), then notes which
 * of their hashes TorBox holds.
 */
function crawl(): Promise<void> {
  crawling ??= (async () => {
    crawlStarted = Date.now()
    const s = await db()
    const dubs = (s.dubs ??= {})
    const fresh: Row[] = []
    for (let n = 1; n <= (s.crawled ? DAILY_PAGES : FIRST_PAGES); n++) {
      const rows = searchRows(await page(`index.php?page=torrents&category=${DUBBED_FILMS}&active=0&internel=0&order=3&by=2&pages=${n}`))
      const unseen = rows.filter((r) => dubs[r.id] === undefined && !fresh.some((x) => x.id === r.id))
      fresh.push(...unseen)
      if (!unseen.length) break
    }
    await crawlShows(s)
    for (const r of fresh) {
      const tmdb = PACK.test(r.title) ? 0 : await tmdbFilm(r.title)
      dubs[r.id] = tmdb
      if (!tmdb) continue
      const f = (s.films[tmdb] ??= { at: 0, ar: '', rows: [] })
      if (!f.rows.some((x) => x.id === r.id)) f.rows.push({ ...r, dub: true })
    }
    s.crawled = Date.now()
    const votes = (s.votes ??= {})
    for (const id of new Set(Object.values(dubs).filter(Boolean).map(String))) {
      if (votes[id] !== undefined) continue
      const n = await tmdbGet<{ vote_count?: number }>(`movie/${id}`).then((r) => r.vote_count, () => undefined)
      if (n !== undefined) votes[id] = n
    }
    await save()
    // Films and shows take turns: shows have far fewer votes than films, and would never get one.
    const films = unhashed(s), shows = unhashedShows(s)
    for (let i = 0; i < Math.max(films.length, shows.length); i++) {
      const film = films[i], show = shows[i]
      if (film && (!s.bare?.[film.id] || allowance(s, true) > KEEP_FOR_ASKED)) await resolve(film, true, KEEP_FOR_ASKED)
      if (show && (!s.bare?.[show.row.id] || allowance(s, true) > KEEP_FOR_ASKED)) await resolvePack(show.row, show.names, KEEP_FOR_ASKED)
    }
    await onTorBox(dubHashes(s), s)
    await save()
  })()
    .catch((e) => void (e instanceof Paused || console.warn(`arabp2p: crawl failed (${e instanceof Error ? e.message : e})`)))
    .finally(() => (crawling = null))
  return crawling
}

/** Each crawled dubbed film's best copy still without a hash: the films with the most TMDB votes first, then Egyptian dubs, then the newest. */
const unhashed = (s: Store) => [...new Set(Object.values(s.dubs ?? {}).filter(Boolean).map(String))]
  .flatMap((id) => wanted(s.films[id]?.rows ?? [], s.torrents).slice(0, 1).filter((r) => !s.torrents[r.id]).map((r) => ({ r, votes: s.votes?.[id] ?? 0 })))
  .sort((a, b) => b.votes - a.votes || Number(b.r.egyptian) - Number(a.r.egyptian) || Number(b.r.id) - Number(a.r.id))
  .map((x) => x.r)

/** The tracker's names for a show's upload without its seasons and episodes ("Bluey S01-02-03" → "Bluey"), as uploadNames reads them. */
export function showNames(title: string): string[] {
  const t = digits(decode(title))
    .replace(/(?<![a-z])s\d{1,2}(?:[ ._-]*(?:s|e)?\d{1,3})*(?![\d])/gi, ' ')
    .replace(/(?:الموسم|الجزء|الحلقة|الحلقات)\s*\d*|\bE\d{1,3}\b|كامل/g, ' ')
  return uploadNames(t).names
}

/** Of TMDB's answers for [q], the animated show: the one named [q] exactly, else (for a name of two words or more) the first. */
export function pickShow(results: { id: number; name?: string; original_name?: string; genre_ids?: number[] }[], q: string): number {
  const fits = results.filter((x) => x.genre_ids?.includes(16))
  return (fits.find((x) => [x.name, x.original_name].some((n) => n && norm(n) === norm(q))) ?? (norm(q).includes(' ') ? fits[0] : undefined))?.id ?? 0
}

/**
 * Reads the dubbed-shows category (FIRST_PAGES the first time, then the newest pages until a known upload) and files
 * each new upload under its TMDB show, with the show's names (Arabic too) and TMDB vote count, once.
 */
async function crawlShows(s: Store): Promise<void> {
  const seen = (s.showDubs ??= {})
  const fresh: Row[] = []
  for (let n = 1; n <= (s.showsCrawled ? DAILY_PAGES : FIRST_PAGES); n++) {
    const rows = searchRows(await page(`index.php?page=torrents&category=${DUBBED_SHOWS}&active=0&internel=0&order=3&by=2&pages=${n}`))
    const unseen = rows.filter((r) => seen[r.id] === undefined && !fresh.some((x) => x.id === r.id))
    fresh.push(...unseen)
    if (!unseen.length) break
  }
  const shows = (s.shows ??= {}), votes = (s.votes ??= {})
  for (const r of fresh) {
    let tmdb = 0
    for (const q of showNames(r.title).slice(0, 3)) {
      const found = await tmdbGet<{ results: Parameters<typeof pickShow>[0] }>(`search/tv?query=${encodeURIComponent(q)}`).catch(() => null)
      if ((tmdb = found ? pickShow(found.results, q) : 0)) break
    }
    seen[r.id] = tmdb
    if (!tmdb) continue
    if (!shows[tmdb]?.names) {
      const [en, ar] = await Promise.all([`tv/${tmdb}`, `tv/${tmdb}?language=ar`].map((p) => tmdbGet<{ name?: string; original_name?: string; vote_count?: number }>(p).catch(() => null)))
      if (!en) continue
      votes[`tv:${tmdb}`] = en.vote_count ?? 0
      shows[tmdb] = { at: shows[tmdb]?.at ?? 0, ar: ar?.name ?? '', rows: shows[tmdb]?.rows ?? [], names: [...new Set([ar?.name, en.name, en.original_name].filter((n): n is string => !!n))] }
    }
    if (!shows[tmdb].rows.some((x) => x.id === r.id)) shows[tmdb].rows.push(r)
  }
  s.showsCrawled = Date.now()
  await save()
}

/** Each crawled dubbed show still without an episode-holding pack: its best pack yet unread (season 1's first), the best-known shows first. */
const unhashedShows = (s: Store) => [...new Set(Object.values(s.showDubs ?? {}).filter(Boolean).map(String))]
  .flatMap((id) => {
    const show = s.shows?.[id]
    if (!show?.names || show.rows.some((r) => { const p = s.packs?.[r.id]; return p && 'hash' in p })) return []
    const row = wantedPacks(show.rows, 1, s.packs).find((r) => !s.packs?.[r.id])
    return row ? [{ row, names: show.names, votes: s.votes?.[`tv:${id}`] ?? 0 }] : []
  })
  .sort((a, b) => b.votes - a.votes)

/** The hashes of every dubbed copy known: films' and shows' packs. */
const dubHashes = (s: Store) => [
  ...Object.values(s.films).flatMap((f) => f.rows.filter((r) => r.dub).flatMap((r) => {
    const h = s.torrents[r.id]
    return h && 'hash' in h ? [h.hash] : []
  })),
  ...Object.values(s.packs ?? {}).flatMap((p) => ('hash' in p ? [p.hash] : [])),
]

/**
 * The library's "Dubbed in Arabic" row: films and shows (title ids) with a dubbed copy (a show: a pack with its
 * episodes) TorBox held in the last 3 days, the newest upload first. Read from the store file, so the library build (another process) can call it.
 */
export async function dubbedFilms(file = storeFile()): Promise<string[]> {
  const s = await readStore(file)
  const recent = (h: string) => Date.now() - (s.tb?.[h] ?? 0) < 3 * DAY_MS
  return Object.entries(s.films).flatMap(([id, f]) => {
    const newest = Math.max(0, ...f.rows.filter((r) => {
      const h = s.torrents[r.id]
      return r.dub && h && 'hash' in h && recent(h.hash)
    }).map((r) => Number(r.id)))
    return newest ? [{ id: `movie:${id}`, newest }] : []
  }).concat(Object.entries(s.shows ?? {}).flatMap(([id, show]) => {
    const newest = Math.max(0, ...show.rows.filter((r) => {
      const p = s.packs?.[r.id]
      return p && 'hash' in p && recent(p.hash)
    }).map((r) => Number(r.id)))
    return newest ? [{ id: `tv:${id}`, newest }] : []
  })).sort((a, b) => b.newest - a.newest).map((x) => x.id)
}

/**
 * A film's ArabP2P copies TorBox holds, from what's known now (the tracker is never waited on): a dubbed
 * animated film's dubs, an Arabic film's copies. `pending` while a lookup for it is running, so the asker
 * comes back. Films that are neither, and a box without the login, get nothing.
 */
export async function arabp2pCopies(tmdb: string, t: TmdbTitle): Promise<{ copies: Omit<RdCopy, 'kbps'>[]; pending: boolean }> {
  const arabic = t.originalLanguage === 'ar'
  if (!process.env.ARABP2P_USER || !process.env.ARABP2P_PASS) return { copies: [], pending: false }
  const s = await db()
  // Any film asked for starts the day's crawl of the dubbed films, in the background, and another (one page)
  // whenever the allowance has room again while dubbed films wait for a hash.
  if (Date.now() >= pausedUntil && (Date.now() - (s.crawled ?? 0) > DAY_MS || (Date.now() - crawlStarted > 30 * 60_000 && allowance(s, true) > KEEP_FOR_ASKED && (unhashed(s).length || unhashedShows(s).length)))) crawl()
  if (!arabic && !t.genres.includes('Animation')) return { copies: [], pending: false }
  if (Date.now() >= pausedUntil && due(s, tmdb, !arabic)) refresh(tmdb, t, arabic)
  const known = (s.films[tmdb]?.rows ?? []).flatMap((row) => {
    const h = s.torrents[row.id]
    return h && 'hash' in h ? [{ row, h }] : []
  })
  const held = await onTorBox([...new Set(known.map((k) => k.h.hash))], s)
  const copies = known.filter((k) => held.has(k.h.hash)).map(({ row, h }) => {
    const a = parseRelease(`${row.title}\n${h.filename}`)
    return {
      src: 'rd' as const, hash: h.hash, file: h.file, filename: h.filename, name: row.title, size: h.size || row.size, seeds: row.seeds,
      ...(process.env.TORBOX_TOKEN && { cached: true as const }),
      ...a,
      // A dub's audio is Arabic ("arz" too for an Egyptian dub; often with the original too); an Arabic film's is the original, named or not.
      langs: row.dub ? [...new Set(['ar', ...(row.egyptian ? ['arz'] : []), ...a.langs])] : a.langs,
    }
  })
  return { copies, pending: jobs.has(tmdb) }
}

// --- Dubbed shows ------------------------------------------------------------------------------------------

/** Whether a show's upload names [season]: "S02", "S01-02-03", "[م2]", "الموسم 2"; one naming no season (a complete pack) does too. */
export function namesSeason(title: string, season: number): boolean {
  const t = digits(title)
  const named = [...t.matchAll(/(?<![a-z])s(\d{1,2})((?:[ ._-]+(?:s)?\d{1,2}(?!\d))*)(?!\d|e\d)/gi)].flatMap((m) => [m[1], ...(m[2].match(/\d+/g) ?? [])])
    .concat([...t.matchAll(/(?:\[م|الموسم\s*|الجزء\s*)(\d{1,2})(?!\d)/g)].map((m) => m[1])).map(Number)
  return !named.length || named.includes(season)
}

/** A show's rows worth a hash for [season], best first: those naming it, then Egyptian dubs, resolution and seeds. */
function wantedPacks(rows: Row[], season: number, packs: Store['packs'] = {}): Row[] {
  const res = (r: Row) => parseRelease(r.title).res
  return rows.filter((r) => !(packs[r.id] && 'none' in packs[r.id]!))
    .sort((a, b) => Number(namesSeason(b.title, season)) - Number(namesSeason(a.title, season)) || Number(b.egyptian) - Number(a.egyptian) || res(b) - res(a) || b.seeds - a.seeds)
    .slice(0, PER_FILM)
}

/** The tracker's category of Arabic-dubbed animated shows (beside the films' 98): a search in all of them gives a show's newest uploads, mostly subtitled. */
const DUBBED_SHOWS = 100

/** A show's torrent's hash and episodes (by the show's [names]), as fetchHash finds them. */
async function resolvePack(row: Row, names: string[], keep: number): Promise<void> {
  const held = await fetchHash(row, true, keep)
  if (!held) return
  const s = await db()
  const eps = packEpisodes(held.files, names)
  ;(s.packs ??= {})[row.id] = Object.keys(eps).length ? { hash: held.hash, eps } : { none: true }
  await save()
}

const showJobs = new Map<string, Promise<void>>()
/** Brings a show's rows (its dubbed uploads, by name) and [season]'s packs up to date, in the background. */
function refreshShow(tmdb: string, t: TmdbTitle, season: number): Promise<void> {
  let job = showJobs.get(tmdb)
  if (!job) {
    job = (async () => {
      const s = await db()
      const shows = (s.shows ??= {})
      const packs = (s.packs ??= {})
      let show = shows[tmdb]
      const ar = show?.ar ?? await tmdbGet<{ name?: string }>(`tv/${tmdb}?language=ar`).then((r) => r.name ?? '', () => '')
      const names = [...new Set([ar, t.name, t.originalTitle].filter(Boolean))]
      if (stale(show)) {
        const crawled = show?.rows ?? []
        const rows = new Map<string, Row>()
        for (const q of names.slice(0, 2)) {
          const html = await page(`index.php?page=torrents&search=${encodeURIComponent(q)}&category=${DUBBED_SHOWS}&active=0&internel=0&order=3&by=2`)
          for (const r of searchRows(html)) if (names.some((n) => ` ${norm(r.title)} `.includes(` ${norm(n)} `))) rows.set(r.id, r)
        }
        // Rows the crawl added are kept alongside what the search finds.
        show = shows[tmdb] = { at: Date.now(), ar, names, rows: [...rows.values(), ...crawled.filter((r) => !rows.has(r.id))] }
        await save()
      }
      for (const r of wantedPacks(show!.rows, season, packs)) if (!packs[r.id]) await resolvePack(r, names, 0)
    })()
      .catch((e) => void (e instanceof Paused || console.warn(`arabp2p: tv ${tmdb} failed (${e instanceof Error ? e.message : e})`)))
      .finally(() => showJobs.delete(tmdb))
    showJobs.set(tmdb, job)
  }
  return job
}

/**
 * An episode's ArabP2P copies TorBox holds, for an animated show (its Arabic dubs, from known packs; the tracker
 * is never waited on). `pending` while the show's lookup runs.
 */
export async function arabp2pEpisodeCopies(tmdb: string, t: TmdbTitle, season: number, episode: number): Promise<{ copies: Omit<RdCopy, 'kbps'>[]; pending: boolean }> {
  if (!process.env.ARABP2P_USER || !process.env.ARABP2P_PASS || !t.genres.includes('Animation')) return { copies: [], pending: false }
  const s = await db()
  const rows = s.shows?.[tmdb]?.rows ?? []
  const due = stale(s.shows?.[tmdb]) || wantedPacks(rows, season, s.packs).some((r) => !s.packs?.[r.id] && (!s.bare?.[r.id] || allowance(s, true) > 0))
  if (Date.now() >= pausedUntil && due) refreshShow(tmdb, t, season)
  const known = rows.flatMap((row) => {
    const p = s.packs?.[row.id]
    const ep = p && 'eps' in p ? p.eps[`${season}:${episode}`] : undefined
    return p && 'hash' in p && ep ? [{ row, hash: p.hash, ep }] : []
  })
  const held = await onTorBox([...new Set(known.map((k) => k.hash))], s)
  const copies = known.filter((k) => held.has(k.hash)).map(({ row, hash, ep: [file, filename, size] }) => {
    const a = parseRelease(`${row.title}\n${filename}`)
    return {
      src: 'rd' as const, hash, file, filename, name: row.title, size, seeds: row.seeds,
      ...(process.env.TORBOX_TOKEN && { cached: true as const }),
      ...a,
      langs: [...new Set(['ar', ...(row.egyptian ? ['arz'] : []), ...a.langs])],
    }
  })
  return { copies, pending: showJobs.has(tmdb) }
}
