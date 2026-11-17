// The owner's own state, shared by the TV and the stick (POST /api/app/state): favourites, channel and row order, resume
// points, followed shows, Up next dismissals, recent searches, copy pins and languages. One value per key, as the app keeps
// it, with when it was written there; the newest write wins, and a null value (a removal) is kept as such.
import { fileURLToPath } from 'node:url'
import { jsonFile } from './fs.js'

export type StateEntry = { v: string | null; at: number }

const store = jsonFile<Record<string, StateEntry>>(
  () => process.env.APP_STATE_FILE ?? fileURLToPath(new URL('../.cache/app-state.json', import.meta.url)),
  () => ({}),
)

/** Folds [put] in, each key only when it is newer than the one kept; answers every key kept. */
export const exchangeState = (put: Record<string, StateEntry>) =>
  store.update((cur) => {
    const newer = Object.entries(put).filter(([k, e]) => !cur[k] || e.at > cur[k].at)
    return newer.length ? { ...cur, ...Object.fromEntries(newer) } : cur
  })
