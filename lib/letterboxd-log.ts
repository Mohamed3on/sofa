// Films to log on Letterboxd for the owner, kept until their browser has logged them.
// Letterboxd has no API for personal projects, and Cloudflare refuses every write that isn't a real browser's (plain
// requests, headless Chrome and an Android WebView alike, 2026-11-07), so the box only keeps the queue: the stick's marks
// and finished plays fold in here, and the TrueScore extension in the owner's Chrome takes it (/api/letterboxd/logs),
// logs each film on letterboxd.com and says which it logged. One entry per film, the latest marks folded in.
import { fileURLToPath } from 'node:url'
import { jsonFile } from './fs.js'

/** A film to log: TMDB's [tmdb] id, when it was watched (or marked) [at] (epoch ms; the browser dates it in its own zone), the owner's [rating] out of 10. */
export interface LetterboxdLog { tmdb: number; at: number; rating?: number }

const store = jsonFile<LetterboxdLog[]>(
  () => process.env.LETTERBOXD_LOG_FILE ?? fileURLToPath(new URL('../.cache/letterboxd-log.json', import.meta.url)),
  () => [],
)

/**
 * Folds a mark or a finished play of [title] in: watched (on [at]) or rated, the film waits to be logged, keeping a
 * rating given before; a rating cleared goes; marked unwatched before it went out, it doesn't go. Anything but a film
 * (movie:N) is left out: Letterboxd logs films.
 */
export async function noteMark(title: string, { watched, rating, at = Date.now() }: { watched?: boolean; rating?: number | null; at?: number }): Promise<void> {
  const tmdb = Number(title.match(/^movie:(\d+)$/)?.[1])
  if (!tmdb) return
  const all = store.read(true)
  const held = all.find((l) => l.tmdb === tmdb)
  const rest = all.filter((l) => l.tmdb !== tmdb)
  const stars = rating === undefined ? held?.rating : rating ?? undefined
  if (watched === false || (!watched && !held && !stars)) return held ? store.write(rest) : undefined
  await store.write([...rest, { tmdb, at: watched ? at : held?.at ?? at, ...(stars && { rating: stars }) }])
}

/** The films waiting to be logged, oldest first. */
export const pendingLogs = (): LetterboxdLog[] => store.read(true).slice().sort((a, b) => a.at - b.at)

/** Takes out the [done] logs the browser sent; an entry whose marks changed since (a new [at] or rating) stays. */
export async function logged(done: LetterboxdLog[]): Promise<void> {
  const sent = new Set(done.map((l) => `${l.tmdb}:${l.at}:${l.rating ?? ''}`))
  const all = store.read(true)
  const left = all.filter((l) => !sent.has(`${l.tmdb}:${l.at}:${l.rating ?? ''}`))
  if (left.length !== all.length) await store.write(left)
}
