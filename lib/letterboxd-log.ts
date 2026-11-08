// What the TV asked to change on Letterboxd for the owner, kept until their browser has made the changes.
// Letterboxd has no API for personal projects, and Cloudflare refuses every write that isn't a real browser's (plain
// requests, headless Chrome and an Android WebView alike, 2026-11-07), so the box only keeps the queue: the stick's marks,
// ratings, finished films and watchlist removals fold in here, and the TrueScore extension in the owner's Chrome takes it
// (/api/letterboxd/logs), makes each change on letterboxd.com and says which it made. One entry per film, the latest
// asks folded in. The box also keeps which diary entries the app made, so marking a film unwatched deletes those only.
import { fileURLToPath } from 'node:url'
import { jsonFile } from './fs.js'

/** What one film should come to on Letterboxd, each part only when the TV asked for it. */
export interface LetterboxdLog {
  /** TMDB's id of the film. */
  tmdb: number
  /** When the latest change was asked (epoch ms): a new diary entry's date, in the browser's own day. */
  at: number
  /** A diary entry to add, dated [at], with the [rating] when one is set. */
  log?: true
  /** The film's rating to set, out of 10 (Letterboxd's half stars), or null to clear it. */
  rating?: number | null
  /** The diary entries the app made for the film (Letterboxd ids) to delete: it was marked unwatched. */
  unlog?: string[]
  /** Onto the Letterboxd watchlist or off it. */
  watchlist?: boolean
}

/** A change the browser made: the log as it was sent, and the Letterboxd id of the diary entry it added, if any. */
export type Done = LetterboxdLog & { made?: string }

interface Store {
  pending: LetterboxdLog[]
  /** The diary entries the app made, by TMDB id. */
  made: Record<string, string[]>
}

const store = jsonFile<Store | LetterboxdLog[]>(
  () => process.env.LETTERBOXD_LOG_FILE ?? fileURLToPath(new URL('../.cache/letterboxd-log.json', import.meta.url)),
  () => ({ pending: [], made: {} }),
)
// The first queue was a bare list of logs.
const current = (s: Store | LetterboxdLog[]): Store => (Array.isArray(s) ? { pending: s.map((l) => ({ ...l, log: true as const })), made: {} } : s)
const read = (): Store => current(store.read(true))
const change = (f: (s: Store) => Store) => store.update((s) => f(current(s)))

const isEmpty = (l: LetterboxdLog) => !l.log && l.rating === undefined && !l.unlog?.length && l.watchlist === undefined

function put(s: Store, tmdb: number, next: LetterboxdLog | null): Store {
  const rest = s.pending.filter((l) => l.tmdb !== tmdb)
  return { ...s, pending: next && !isEmpty(next) ? [...rest, next] : rest }
}

/**
 * Folds a change the TV made to [title] in. Watched (on [at]): a diary entry, unless the app's earlier ones were about
 * to be deleted, which then stay. Unwatched: an entry not yet added never is, and the ones the app made are deleted.
 * A rating set or cleared and a watchlist change go as asked. Anything but a film (movie:N) is left out.
 */
export async function noteMark(title: string, mark: { watched?: boolean; rating?: number | null; watchlist?: boolean; at?: number }): Promise<void> {
  const tmdb = Number(title.match(/^movie:(\d+)$/)?.[1])
  if (!tmdb) return
  const at = mark.at ?? Date.now()
  await change((s) => {
    const next: LetterboxdLog = { ...(s.pending.find((l) => l.tmdb === tmdb) ?? { tmdb, at: 0 }) }
    if (mark.watched === true) {
      if (next.unlog) delete next.unlog
      else Object.assign(next, { log: true, at })
    } else if (mark.watched === false) {
      delete next.log
      const made = s.made[tmdb] ?? []
      if (made.length) next.unlog = made
    }
    if (mark.rating !== undefined) next.rating = mark.rating
    if (mark.watchlist !== undefined) next.watchlist = mark.watchlist
    if (!next.at) next.at = at
    return put(s, tmdb, next)
  })
}

/** The changes waiting for the browser, oldest first. */
export const pendingLogs = (): LetterboxdLog[] => read().pending.slice().sort((a, b) => a.at - b.at)

/**
 * Takes out what the browser [done]: each part it made leaves its film's entry (a part asked again since, differently,
 * stays), the diary entries it added join the app's own, and the ones it deleted leave them.
 */
export async function logged(done: Done[]): Promise<void> {
  await change((s) => {
    const made = { ...s.made }
    let pending = s.pending
    for (const d of done) {
      if (d.made) made[d.tmdb] = [...(made[d.tmdb] ?? []), d.made]
      if (d.unlog) made[d.tmdb] = (made[d.tmdb] ?? []).filter((id) => !d.unlog!.includes(id))
      if (!made[d.tmdb]?.length) delete made[d.tmdb]
      pending = pending.flatMap((l) => {
        if (l.tmdb !== d.tmdb) return [l]
        const left: LetterboxdLog = { ...l }
        if (d.log && l.log && l.at === d.at) delete left.log
        if (d.rating !== undefined && l.rating === d.rating) delete left.rating
        if (d.unlog && l.unlog) left.unlog = l.unlog.filter((id) => !d.unlog!.includes(id))
        if (d.watchlist !== undefined && l.watchlist === d.watchlist) delete left.watchlist
        return isEmpty(left) ? [] : [left]
      })
    }
    return { pending, made }
  })
}
