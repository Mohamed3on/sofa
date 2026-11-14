// An episode's skippable parts, for the player's Skip intro / Skip recap button and its next-episode card (R33,
// U17): TheIntroDB by TMDB id first, IntroDB by IMDb id for whatever TheIntroDB lacks (between them 29 of the
// owner's last 30 episodes had an intro on 2026-11-05). Free, keyless, community-made. Each episode's answer is
// kept on disk; it rides along with the episode's copies.
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { writeAtomic } from './fs.js'
import { getJson, HttpError } from './http.js'
import { parseTitleId } from './tmdb.js'

/** [start, end] in ms; a credits end of 0 means "to the end of the file". */
export type Intro = [number, number]

/** An episode's intro, recap and end credits, each absent when unknown. */
export interface Segments { intro?: Intro; recap?: Intro; credits?: Intro }

type Span = { start_ms: number | null; end_ms: number | null }
interface TheIntroDb { intro?: Span[]; recap?: Span[]; credits?: Span[] }
interface IntroDb { intro?: Span | null; recap?: Span | null; outro?: Span | null }

const FOUND_MS = 30 * 864e5
const NONE_MS = 864e5
/** Shorter than this, a prompt would outlast the part. */
const MIN_MS = 5000
const dir = () => process.env.INTROS_DIR ?? fileURLToPath(new URL('../.cache/intros', import.meta.url))

/** The first usable [start, end] window (a null start = the episode's first frame). */
function windowOf(spans: (Span | null | undefined)[]): Intro | undefined {
  for (const s of spans) {
    if (!s || s.end_ms == null) continue
    const start = s.start_ms ?? 0
    if (s.end_ms - start >= MIN_MS) return [Math.round(start), Math.round(s.end_ms)]
  }
}

/** Where the end credits start ([start, end], end 0 when they run to the end); a start of 0 is a placeholder. */
function credits(spans: (Span | null | undefined)[]): Intro | undefined {
  const s = spans.find((s) => s?.start_ms != null && s.start_ms > 0)
  return s ? [Math.round(s.start_ms!), Math.round(s.end_ms ?? 0)] : undefined
}

/**
 * TheIntroDB's measured times are exact to the ms; a credits start on a whole second is a filled-in guess (Bluey
 * s2e3–e9 all say 405000, 6–19 s before the real credits), and the card counting down from it cuts the ending.
 */
const measured = (s: Span) => s.start_ms == null || s.start_ms % 1000 !== 0

/** Both answers merged, TheIntroDB's first. */
export function segmentsOf(tidb: TheIntroDb | null, idb: IntroDb | null): Segments {
  const intro = windowOf([...(tidb?.intro ?? []), idb?.intro])
  const recap = windowOf([...(tidb?.recap ?? []), idb?.recap])
  const end = credits([...(tidb?.credits ?? []).filter(measured), idb?.outro])
  return { ...(intro && { intro }), ...(recap && { recap }), ...(end && { credits: end }) }
}

const none = (e: unknown) => {
  if (e instanceof HttpError && e.status === 404) return null
  throw e
}

const found = (s: Segments) => !!(s.intro || s.recap || s.credits)

/** The parts of episode [title] ("tv:1396:s1e1") of the show with IMDb id [imdb]; empty when neither source has any. */
export async function segmentsFor(title: string, imdb?: string): Promise<Segments> {
  const p = parseTitleId(title)
  if (p?.kind !== 'tv' || p.season == null) return {}
  const { tmdb, season, episode } = p
  const file = `${dir()}/${title.replace(/:/g, '_')}.json`
  // Files from before segments hold only { at, intro }: they are asked again once, and their intro is kept if both sources now fail.
  const kept = await readFile(file, 'utf8').then((s) => JSON.parse(s) as { at: number; intro?: Intro | null; segments?: Segments }).catch(() => null)
  if (kept?.segments && Date.now() - kept.at < (found(kept.segments) ? FOUND_MS : NONE_MS)) return kept.segments
  const tidb = await getJson<TheIntroDb>(`https://api.theintrodb.org/v2/media?tmdb_id=${tmdb}&season=${season}&episode=${episode}`, { timeoutMs: 4000, retries: 0 }).catch(none)
  let segments = segmentsOf(tidb, null)
  if (imdb && !(segments.intro && segments.credits)) {
    const idb = await getJson<IntroDb>(`https://api.introdb.app/segments?imdb_id=${imdb}&season=${season}&episode=${episode}`, { timeoutMs: 4000, retries: 0 }).catch(none)
    segments = segmentsOf(tidb, idb)
  }
  if (!found(segments) && kept?.intro) segments = { intro: kept.intro }
  await writeAtomic(file, { at: Date.now(), intro: segments.intro ?? null, segments })
  return segments
}
