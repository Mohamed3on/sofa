// An episode's intro, for the player's Skip intro prompt (R33, U17): TheIntroDB by TMDB id first, IntroDB by
// IMDb id when it has none (between them 29 of the owner's last 30 episodes on 2026-11-05). Free, keyless,
// community-made. Each episode's answer is kept on disk; it rides along with the episode's copies.
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { writeAtomic } from './fs.js'
import { getJson, HttpError } from './http.js'
import { parseTitleId } from './tmdb.js'

/** [start, end] in ms. */
export type Intro = [number, number]

interface TheIntroDb { intro?: { start_ms: number | null; end_ms: number | null }[] }
interface IntroDb { intro?: { start_ms: number; end_ms: number } | null }

const FOUND_MS = 30 * 864e5
const NONE_MS = 864e5
/** Shorter than this, a prompt would outlast the intro. */
const MIN_MS = 5000
const dir = () => process.env.INTROS_DIR ?? fileURLToPath(new URL('../.cache/intros', import.meta.url))

/** The first usable intro window in either answer (TheIntroDB's null start = the episode's first frame). */
function introWindow(tidb: TheIntroDb | null, idb: IntroDb | null): Intro | null {
  const spans = [...(tidb?.intro ?? []).map((s) => [s.start_ms ?? 0, s.end_ms] as const), ...(idb?.intro ? [[idb.intro.start_ms, idb.intro.end_ms] as const] : [])]
  const w = spans.find(([start, end]) => end != null && end - start >= MIN_MS)
  return w ? [Math.round(w[0]), Math.round(w[1]!)] : null
}

const none = (e: unknown) => {
  if (e instanceof HttpError && e.status === 404) return null
  throw e
}

/** The intro of episode [title] ("tv:1396:s1e1") of the show with IMDb id [imdb]; null when neither source has one. */
export async function introFor(title: string, imdb?: string): Promise<Intro | null> {
  const p = parseTitleId(title)
  if (p?.kind !== 'tv' || p.season == null) return null
  const { tmdb, season, episode } = p
  const file = `${dir()}/${title.replace(/:/g, '_')}.json`
  const kept = await readFile(file, 'utf8').then((s) => JSON.parse(s) as { at: number; intro: Intro | null }).catch(() => null)
  if (kept && Date.now() - kept.at < (kept.intro ? FOUND_MS : NONE_MS)) return kept.intro
  const tidb = await getJson<TheIntroDb>(`https://api.theintrodb.org/v2/media?tmdb_id=${tmdb}&season=${season}&episode=${episode}`, { timeoutMs: 4000, retries: 0 }).catch(none)
  let intro = introWindow(tidb, null)
  if (!intro && imdb) intro = introWindow(null, await getJson<IntroDb>(`https://api.introdb.app/segments?imdb_id=${imdb}&season=${season}&episode=${episode}`, { timeoutMs: 4000, retries: 0 }).catch(none))
  await writeAtomic(file, { at: Date.now(), intro })
  return intro
}
