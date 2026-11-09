// Probe REAL video metadata (resolution/fps/codec) for the channels that CLAIM
// >=1080p (marker tier q>=4: FHD/RAW/UHD/8K) and cache it, so curation can sort on
// truth instead of the provider's lying "UHD"/"RAW" tags. We skip the ~880 feeds
// whose markers already say <1080p (HD/SD/unmarked) — no point probing those.
//
// Fast + low-overhead: ffprobe reads stream headers (SPS/VPS) with a tiny -probesize
// and six seconds of packets, and exits (no decode → CPU near-idle). The account reports one
// connection, but the provider gives one machine many streams at once (16 from one Mac: 15 pictures in
// 9 s, 2026-11-08), and its VPN front two machines at once, so [concurrency] feeds are probed at a time.
// A machine the normal fronts refuse (HTTP 511: a VPN, a datacenter, GitHub's runners) probes through
// the VPN front.
//
// WORKS ON A COPY: it reads the readings from QUALITY_FILE (deploy/probe-quality.sh points
// it at a copy of the box's store) and writes only the entries it changed to PROBED_FILE,
// atomically every ~2s and at the end. The box merges those into its live store
// (scripts/merge-quality.ts), so readings the app records meanwhile are kept.
//
// REFRESH MODE (`refresh` arg): re-probe ONLY feeds not yet confirmed at their marker
// tier — those missing a reading (dead ones weekly), or cached BELOW their stated quality
// (e.g. a "3840p" feed reading 1080p, probably idle and may return higher). Feeds already at/above their
// marker are left alone — nothing to gain. Retry event-only feeds (live only during a match); OVERWRITE a cached reading
// only when strictly better (height, then fps) — monotonic, so an idle/lower probe never
// downgrades a known peak. Repeated runs thus chase the gaps and converge on each peak.
//
// RE-VERIFICATION: a peak can go stale — a feed's source changes, or an adaptive restream
// was caught at its best moment once — and the monotonic rule would keep it forever. So
// every reading carries the day it was taken (`at`), and refresh first re-probes readings
// older than STALE_DAYS (in playlist order), REPLACING them with what the feed shows now. A
// below-marker result is chased back up by the gap pass on later runs; PPV/event feeds
// are floored at their marker by channelPicture in lib/curation.ts, so an idle re-probe can't bury them.
// A re-probe with NO signal proves nothing (dead feed, or a refused connection): the
// reading stays due, and `tried` only moves it to the back of the queue so dead feeds
// don't eat every night's budget.
//
// BUSY SLOT: a run starts only when the account's slot is free (no one watching). A probe refused as busy
// (HTTP 458) holds new probes until the run's own streams have closed, then asks the account: a slot taken
// still is someone watching, so the run stops instead of recording their channel-switches as dead feeds;
// a free one was a passing refusal (two in 650 probes, 2026-11-09), so the feed is tried again and the run goes on. Stopped by hand (Ctrl-C) or by a signal, it saves what it measured
// for the wrapper to merge; the next run takes up the rest.
//
// TIME BUDGET (`mins=N`, default 30): stop starting new probes once the budget is spent.
// Refresh targets are by definition the feeds that DIDN'T reach their marker — idle or
// dead ones that burn the full ffprobe timeout — and at the one connection this account
// allows, the ~900-feed backlog is several hours of wall clock. Unbounded, that either
// blows GitHub's 6h job limit or holds the only connection slot all day. Bounded, each
// run takes a predictable bite and the monotonic cache keeps the progress.
//
// BITRATE: the same read counts six seconds of the stream's packets, so each reading with a picture
// carries the feed's bitrate (Mbps, video and audio), added to the ones the app measured while watching
// (lib/quality.ts mergeProbed). Refresh also visits fresh feeds with a picture but no bitrate yet, after
// the stale and below-marker ones, in playlist order, so the 🏆 groups' feeds get theirs first.
//
// LIVE MODE (`live` arg, the daytime timers): every feed of a channel whose guide shows a 🏆 match on air
// now, whatever its reading, so bitrates come from match pictures rather than the still frames idle channels
// show at night (recorded as in refresh). The server's built guide (/api/epg) says what is on air.
//
// Usage: bun scripts/probe-quality.ts [concurrency] [refresh|live] [mins=N]  (creds from env/.env.local)
import { statSync } from 'node:fs'
import { guideCompetitions } from '../lib/app-feed.js'
import { EVENT_ONLY, fetchCuratedChannels, isAbroad, saneFps, withCompetitionGroups, type Channel } from '../lib/curation.js'
import { configFromEnv, connectionSlots as slotsOf, streamUrl, VPN_HOST } from '../lib/provider.js'
import { writeAtomic } from '../lib/fs.ts'
// better() keeps the cache monotonic in refresh mode; the app's readings (/api/app/quality) share these rules.
import { allReadings, better, STALE_DAYS, type Meta, type Readings } from '../lib/quality.ts'

const cfg = configFromEnv()
const requestedConcurrency = Number(Bun.argv[2]) || 1
const LIVE = Bun.argv.includes('live')
const REFRESH = LIVE || Bun.argv.includes('refresh')
const BUDGET_MIN = Number(Bun.argv.find((a) => a.startsWith('mins='))?.slice(5)) || 30
const PROBED = process.env.PROBED_FILE ?? '.cache/quality-probed.json'
const today = new Date().toISOString().slice(0, 10)
const staleBefore = new Date(Date.now() - STALE_DAYS * 864e5).toISOString().slice(0, 10)
const isStale = (m: Meta | null | undefined): boolean => !!m && !(m.at && m.at >= staleBefore)

const connectionSlots = () => slotsOf(cfg)

{
  const { max, active } = await connectionSlots()
  if (active >= max) {
    // Not an error: the nightly run just skips a night when someone's watching.
    console.log(`No free IPTV connection slots (${active}/${max}); someone's watching, so no probing this run`)
    process.exit(0)
  }
}
const CONC = Math.max(1, requestedConcurrency)


const cache = allReadings()
const probed: Readings = {} // what this run changed, for the box to merge

const channels = await fetchCuratedChannels(cfg, false) // live feeds only; VOD is never probed
// A feed "matches its marker" when its real height reaches the tier's nominal resolution.
const nominalH = (q: number) => (q >= 5 ? 2160 : q >= 4 ? 1080 : q >= 3 ? 720 : 0)
// Probe in playlist order (🏆 groups first), so the channels at the top get checked first; the abroad
// buckets' feeds (listed only under 🏆 groups, when their picture holds up) after every in-region one.
const rank = new Map<number, number>()
for (const c of withCompetitionGroups(channels)) if (!rank.has(c.streamId)) rank.set(c.streamId, rank.size)
const live = channels.filter((c) => !c.isEventSlot && !c.vodExt)
  .sort((a, b) => Number(isAbroad(a.group)) - Number(isAbroad(b.group)) || (rank.get(a.streamId) ?? 1e9) - (rank.get(b.streamId) ?? 1e9))
// Re-verify (refresh only): every feed with a stale reading, whatever its marker; ones that
// last had no signal go to the back.
// Dead feeds (null, or a stale reading whose re-probe got no picture) get one retry a week,
// a seventh of them each night: a revived feed gets found without dead ones eating the budget.
const day = Math.floor(Date.now() / 864e5)
const deadDue = (id: number) => (id + day) % 7 === 0
const stale = REFRESH
  ? live.filter((c) => isStale(cache[c.streamId]) && (!cache[c.streamId]!.tried || deadDue(c.streamId)))
      .sort((a, b) => Number(!!cache[a.streamId]!.tried) - Number(!!cache[b.streamId]!.tried))
  : []
const gaps = live.filter((c) => {
  const m = cache[c.streamId]
  if (isStale(m)) return false
  if (!REFRESH) return (c.q ?? 0) >= 4 && !(c.streamId in cache)
  // Refresh: every never-probed feed, labelled or not; dead ones on their weekly turn.
  if (!(c.streamId in cache)) return true
  if (!m) return deadDue(c.streamId)
  // Chase feeds below their stated tier (likely idle), except a plain "4K"/"UHD" name, which
  // is just a 1080p feed (0 of 339 were 4K on 2026-11-01); only a 3840p/2160p tag pays off.
  return (c.q ?? 0) >= 4 && m.h < nominalH(c.q ?? 0) && (c.q! < 5 || /³⁸⁴⁰|3840|2160/.test(c.name))
})
const noBitrate = REFRESH ? live.filter((c) => { const m = cache[c.streamId]; return m && !isStale(m) && !m.mbps }) : []
async function onAir(feeds: Channel[]): Promise<Channel[]> {
  const res = await fetch(`${process.env.API_BASE ?? 'http://127.0.0.1:8790'}/api/epg?t=${process.env.PLAYLIST_TOKEN}`)
  if (!res.ok) throw new Error(`guide: HTTP ${res.status}`)
  const { rows, comps } = guideCompetitions(channels, await res.text())
  const now = Date.now() / 1000
  const showing = new Set(rows.filter((r, i) => comps[i] && r.start <= now && r.stop > now).map((r) => r.tvgId))
  return feeds.filter((c) => showing.has(c.tvgId))
}
const targets = LIVE ? await onAir(live) : [...stale, ...gaps, ...noBitrate]
if (LIVE) console.log(`${targets.length} feeds show a match now`)
const byId = new Map(targets.map((c) => [c.streamId, c.name]))
const ids = [...byId.keys()]

// Progressive save: one debounced writer flushes this run's changes atomically.
let dirty = false
async function flush() {
  if (!dirty) return
  dirty = false
  await writeAtomic(PROBED, probed)
}
const timer = setInterval(flush, 2000)

// The normal fronts answer HTTP 511 to a VPN or datacenter address; the VPN front takes it.
const front = await (async () => {
  if (ids[0] === undefined) return cfg.host
  const r = await fetch(streamUrl(cfg, 'live', ids[0]), { redirect: 'manual', headers: { 'User-Agent': 'VLC/3.0.18' }, signal: AbortSignal.timeout(8000) }).catch(() => null)
  await r?.body?.cancel()
  return r?.status === 511 ? VPN_HOST : cfg.host
})()
if (front !== cfg.host) console.log('The provider refuses this address on its normal fronts (HTTP 511), so probing through its VPN front')
// ffprobe's words for an HTTP 4XX other than 400/401/403/404: the provider's 458, the slot taken by a viewer.
const BUSY = /4XX Client Error, but not one of 40\{0,1,3,4\}/

async function probe(id: number): Promise<Meta | null> {
  const url = streamUrl({ ...cfg, host: front }, 'live', id)
  const p = Bun.spawn(
    ['ffprobe', '-v', 'error', '-user_agent', 'VLC/3.0.18',
     // rw_timeout fails a stalled/dead stream at the network layer fast (the old
     // run's slowness was idle feeds sitting until a long timeout); 2M probesize
     // grabs ~1 keyframe for resolution+fps, then 6 s of packets give the bitrate.
     '-rw_timeout', '8000000', '-probesize', '2M', '-analyzeduration', '2M', '-read_intervals', '%+6',
     '-show_entries', 'stream=index,codec_type,width,height,codec_name,r_frame_rate,avg_frame_rate,field_order,profile,channels:packet=stream_index,pts_time,size',
     '-of', 'json', url],
    { stdout: 'pipe', stderr: 'pipe', timeout: 16000 },
  )
  const [out, err] = await Promise.all([new Response(p.stdout).text(), new Response(p.stderr).text()])
  await p.exited
  if (BUSY.test(err)) refused.add(id)
  try {
    const streams: any[] = JSON.parse(out)?.streams ?? []
    const v = streams.find((s) => s.codec_type === 'video')
    if (v?.width) {
      const rate = (r: unknown) => { const [n, d] = String(r ?? '').split('/').map(Number); return d ? Math.round((n / d) * 10) / 10 : n }
      // Interlaced feeds report their FIELD rate as r_frame_rate (1080i25 reads 50), so
      // they'd tie with true 50p; avg_frame_rate carries the frame rate (half when absent).
      const interlaced = /^(tt|bb|tb|bt)$/.test(v.field_order ?? '')
      // saneFps drops container tick-rate misreads (240/250/300) so `better` can't
      // lock one in as a feed's peak and hide its real rate forever.
      const fps = saneFps(interlaced ? rate(v.avg_frame_rate) || rate(v.r_frame_rate) / 2 : rate(v.r_frame_rate))
      // Atmos = E-AC-3 with JOC; the same 2M read already carries the audio header.
      const atmos = streams.some((s) => s.codec_type === 'audio' && /atmos/i.test(s.profile ?? ''))
      const surround = streams.some((s) => s.codec_type === 'audio' && s.channels >= 6)
      // Every packet's bytes over the span of the video's timestamps; under 3 s of picture is too little to tell.
      const packets: any[] = JSON.parse(out)?.packets ?? []
      const times = packets.filter((p) => p.stream_index === v.index).map((p) => Number(p.pts_time)).filter(Number.isFinite)
      const span = times.length ? Math.max(...times) - Math.min(...times) : 0
      const mbps = span >= 3 ? Math.round(packets.reduce((n, p) => n + (Number(p.size) || 0), 0) * 8 / span / 1e5) / 10 : 0
      return { w: v.width, h: v.height, fps, codec: v.codec_name, name: byId.get(id)!, ...(atmos && { atmos: true as const }), ...(surround && { surround: true as const }), ...(mbps >= 0.1 && { mbps: [mbps] }) }
    }
  } catch { /* unparseable / no video */ }
  return null
}

// Retry flaky feeds (they idle/flicker between events); keep the best reading seen.
async function probeBest(id: number, tries: number): Promise<Meta | null> {
  let best: Meta | null = null
  for (let i = 0; i < tries; i++) {
    const m = await probe(id)
    if (better(m, best)) best = m
    if (best && best.h >= 2160) break
  }
  return best
}

const t0 = Date.now()
const deadline = t0 + BUDGET_MIN * 60_000
let cursor = 0
let busy = false
let ok = 0 // probes that actually returned video this run
// On the box the app's watch log tells when the stick starts playing (it sends each tune within a minute):
// the run stops then, whatever the account's count, which includes the run's own streams.
const EVENTS = process.env.APP_EVENTS_FILE ?? '.cache/app-events.jsonl'
const sizeOf = (f: string) => statSync(f, { throwIfNoEntry: false })?.size ?? 0
const eventsFrom = sizeOf(EVENTS)
let eventsAt = 0
async function appTuned(): Promise<boolean> {
  if (Date.now() - eventsAt < 5000) return false
  eventsAt = Date.now()
  const size = sizeOf(EVENTS)
  if (size === eventsFrom) return false
  return /"type":"tune"/.test(await Bun.file(EVENTS).slice(size < eventsFrom ? 0 : eventsFrom).text()) // smaller: rotated
}
const refused = new Set<number>() // feeds a probe of which was refused as busy
// One check at a time, while no new probe starts: two free readings in a row within 45 s (the panel counts a
// stream for ~5 s after it closes, and in-flight probes take up to 16 s) say no one is watching.
let checking: Promise<boolean> | null = null
const noViewer = () => (checking ??= (async () => {
  const t = Date.now()
  let free = 0
  while (Date.now() - t < 45_000) {
    await Bun.sleep(2000)
    const { max, active } = await connectionSlots()
    free = active < max ? free + 1 : 0
    if (free === 2) return true
  }
  return false
})().finally(() => { checking = null }))
// Stopped by hand or by a signal: what was measured is saved for the wrapper to merge (none of it if no
// feed gave a picture, which says the provider refused this machine), and the next run takes up the rest.
for (const sig of ['SIGINT', 'SIGTERM'] as const) {
  process.on(sig, async () => {
    if (!ok) for (const k of Object.keys(probed)) delete probed[k]
    dirty = true
    await flush()
    console.log(`Stopped after ${ok} pictures; the next run takes up the rest.`)
    process.exit(130)
  })
}
const worker = async () => {
  while (cursor < ids.length && Date.now() < deadline && !busy) {
    if (checking && !(await checking)) break
    if (await appTuned()) { busy = true; console.log('The app started playing, so the run stops'); break }
    const id = ids[cursor++]
    const old = cache[id]
    const reverify = REFRESH && isStale(old)
    // Only event-only feeds earn retries: a 24/7 "4K" feed reading 1080p is usually just 1080p.
    const tries = REFRESH && !reverify && EVENT_ONLY.test(byId.get(id)!) ? 3 : 1
    let m = await probeBest(id, tries)
    if (refused.delete(id)) {
      if (!(await noViewer())) { busy = true; console.log(`Refused as busy on ${byId.get(id)}, and the slot stays taken`); break } // this feed stays due
      m = await probeBest(id, tries)
      if (refused.delete(id)) continue // refused again: due next run
    }
    if (m) ok++
    // Initial: record result (null = probed). Re-verify: the current reading replaces the
    // stale one; no signal keeps it due, just tried (see RE-VERIFICATION).
    // Gaps: only upgrade, never downgrade a fresh peak; a missing/dead one records null again.
    if (!REFRESH || !old) cache[id] = m && { ...m, at: today }
    else if (reverify) cache[id] = m ? { ...m, at: today } : { ...old!, tried: today }
    else if (better(m, old)) cache[id] = { ...m!, at: today }
    // Not better, but a bitrate: the reading stays as it was, with this bitrate for the box to add.
    else if (m?.mbps && old) cache[id] = { ...old, mbps: m.mbps }
    else continue
    probed[id] = cache[id]
    dirty = true
  }
}
await Promise.all(Array.from({ length: Math.min(CONC, ids.length) }, worker))
clearInterval(timer)
const done = Math.min(cursor, ids.length)
if (busy) {
  console.log(`Stopped: the connection slot is in use (someone's watching) — ${ids.length - done} feeds left for the next run.`)
} else if (done < ids.length) {
  console.log(`Budget of ${BUDGET_MIN}min reached — ${ids.length - done} feeds left for the next run.`)
}
// Not one picture means the provider refused this machine (GitHub runners can get HTTP 511,
// like the box), not that every feed died: fail loudly so deploy/probe-quality.sh merges nothing.
if (done > 0 && ok === 0 && !busy) {
  console.error(`No feed returned a picture (${done} tried) — the provider is refusing this machine, so this run is discarded.`)
  process.exit(1)
}
dirty = true; await flush()

// Count over what this run actually ATTEMPTED (`done`), not the whole target list —
// a budgeted run stops early, and reporting the backlog as "probed" overstates it.
const attempted = ids.slice(0, done)
const total = Object.keys(cache).length
console.log(`${REFRESH ? 'Refreshed' : 'Probed'} ${attempted.length}/${ids.length} feeds in ${((Date.now() - t0) / 1000).toFixed(1)}s @ conc=${CONC} — ${ok} have signal, ${Object.keys(probed).length} changed (store has ${total})`)
