// The feed-quality store: every feed's real picture and audio, from the nightly ffprobe run
// (scripts/probe-quality.ts, merged on the box by scripts/merge-quality.ts) and from the Fire TV
// app, which reports what it measures on the streams it plays (/api/app/quality, /api/app/bitrate).
// Kept as JSON in .cache/quality.json, written atomically, and read again whenever the file
// changes, so a merge by another process takes effect without a restart.
import { fileURLToPath } from 'node:url'
import { jsonFile } from './fs.js'

export type Meta = { w: number; h: number; fps: number; codec: string; name: string; atmos?: true; surround?: true; at?: string; tried?: string; mbps?: number[]; low?: string }

/** Feed id → its reading, or null when probed with no picture. */
export type Readings = Record<string, Meta | null>

/** A reading older than this is replaced by the next one, whatever it shows. */
export const STALE_DAYS = 14
export const staleBefore = (today: string) => new Date(Date.parse(today) - STALE_DAYS * 864e5).toISOString().slice(0, 10)

/** A feed's audio: 2 for Atmos, 1 for 5.1 or more, 0 for stereo (or not yet read). */
export const audioRank = (m: Meta | null | undefined): number => (m?.atmos ? 2 : m?.surround ? 1 : 0)

/** Strictly better reading: height, then fps, then audio (Atmos over 5.1 over stereo). */
export const better = (a: Meta | null, b: Meta | null | undefined): boolean =>
  !!a && (!b ? true : a.h !== b.h ? a.h > b.h : a.fps !== b.fps ? a.fps > b.fps : audioRank(a) > audioRank(b))

/**
 * The app's reading merged into a cache entry, or null to keep the entry. Like the probe:
 * fill a missing or dead entry, replace a stale one, upgrade a fresh one. The app's fps
 * counts rendered frames, so a 1080i25 feed reads 50: 50 motion samples a second, which
 * for sport is as smooth as 50p, so it ranks with them. A lower reading (shorter picture
 * or fewer fps) lowers a fresh one only once seen on two different days ([Meta.low] holds
 * the first), so one zap onto a passing fallback or 25 fps slate doesn't demote a good
 * feed; a reading as good in between starts over. A demotion keeps the audio (it's about
 * the picture). (Event feeds keep their marker's tier however low they read.)
 */
export function mergeAppReading(old: Meta | null | undefined, reading: Meta, today: string): Meta | null {
  const next = { ...reading, at: today, ...keepMbps(old) }
  if (!old || !(old.at && old.at >= staleBefore(today))) return next
  if (better(reading, old)) return next
  if (reading.h < old.h || (reading.h === old.h && reading.fps < old.fps)) {
    return old.low && old.low !== today ? { ...next, ...(old.atmos && { atmos: true as const }), ...(old.surround && { surround: true as const }) } : old.low ? null : { ...old, low: today }
  }
  if (!old.low) return null
  const { low: _, ...kept } = old
  return kept
}

/**
 * A probe result merged into the box's entry, or undefined to keep the entry. The probe works on
 * a copy taken when it started, so the entry may have changed since: a reading the app took
 * today stands unless the probe's is strictly better, and bitrates measured meanwhile are kept.
 */
export function mergeProbed(now: Meta | null | undefined, probed: Meta | null, today: string): Meta | null | undefined {
  if (now?.at === today && !better(probed, now)) return undefined
  return probed && { ...probed, ...keepMbps(now) }
}

/**
 * A bitrate the app measured on a feed while playing it (Mbps), kept with the feed's last few, or
 * null when the feed has no reading to keep it on. The feed's bitrate is their median
 * ([medianMbps]): recent, but not moved by one odd reading.
 */
const BITRATE_READINGS = 5
export function addBitrate(old: Meta | null | undefined, mbps: number): Meta | null {
  if (!old) return null
  return { ...old, mbps: [...(old.mbps ?? []), Math.round(mbps * 10) / 10].slice(-BITRATE_READINGS) }
}

/** Spread into a new reading for a feed, so it keeps the bitrates measured on it. */
export const keepMbps = (old: Meta | null | undefined) => (old?.mbps ? { mbps: old.mbps } : {})

export function medianMbps(m: Meta | null | undefined): number | undefined {
  const s = m?.mbps?.toSorted((a, b) => a - b)
  if (!s?.length) return undefined
  const mid = s.length >> 1
  return s.length % 2 ? s[mid] : Math.round((s[mid - 1] + s[mid]) * 5) / 10
}

const store = jsonFile<Readings>(() => process.env.QUALITY_FILE ?? fileURLToPath(new URL('../.cache/quality.json', import.meta.url)), () => ({}))

/** Applies [change] to the latest readings and saves them when it changed any. */
async function update(change: (readings: Readings) => boolean): Promise<boolean> {
  const readings = store.read(true)
  if (!change(readings)) return false
  await store.write(readings)
  return true
}

/** A feed's reading: undefined when never probed, null when probed with no picture. */
export const qualityOf = (id: number | string): Meta | null | undefined => store.read()[String(id)]

/** A copy of every reading (the probe picks its targets from it). */
export const allReadings = (): Readings => ({ ...store.read(true) })

/** Records what the app measured on a feed; true when it changed the feed's entry. */
export const recordReading = (id: number | string, reading: Meta, today: string): Promise<boolean> =>
  update((r) => {
    const merged = mergeAppReading(r[String(id)], reading, today)
    if (merged) r[String(id)] = merged
    return !!merged
  })

/** Records a bitrate the app measured on a feed; true when the feed has an entry to keep it on. */
export const recordBitrate = (id: number | string, mbps: number): Promise<boolean> =>
  update((r) => {
    const merged = addBitrate(r[String(id)], mbps)
    if (merged) r[String(id)] = merged
    return !!merged
  })

/** Merges a probe run's results (only the entries it changed); returns how many entries changed. */
export async function recordProbed(probed: Readings, today: string): Promise<number> {
  let changed = 0
  await update((r) => {
    for (const [id, m] of Object.entries(probed)) {
      const next = mergeProbed(r[id], m, today)
      if (next !== undefined) { r[id] = next; changed++ }
    }
    return changed > 0
  })
  return changed
}
