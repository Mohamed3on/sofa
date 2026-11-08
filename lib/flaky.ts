// Feeds the provider lets down, learned from the app's watch log (/api/app/events). A feed takes a fault
// when the app left it for a sibling feed of the same channel that had no stall, rebuffer or failover, and
// the app no problem, for five minutes either side: the same line at the same moment carried the sibling, so the fault
// was the feed's, not the network's, the route's or the stick's. Back-and-forth between two feeds (both
// failing, or a bad line under both) never counts. A feed with faults on two different days in the last
// 14 is flaky: it sorts after its steady siblings until those days pass.
import { fileURLToPath } from 'node:url'
import { jsonFile } from './fs.js'

export const eventsFile = () => process.env.APP_EVENTS_FILE ?? fileURLToPath(new URL('../.cache/app-events.jsonl', import.meta.url))

type Event = { at: string; type: string; id?: number; from?: number; to?: number }

const STEADY_MS = 5 * 60_000
const WINDOW_DAYS = 14
const TROUBLE = new Set(['stall', 'rebuffer', 'failover', 'problem'])

/** The flaky feed ids in a watch log's text (JSON lines, oldest first), as of [now]. */
export function flakyFeeds(text: string, now = Date.now()): Set<number> {
  const events: Event[] = text.split('\n').flatMap((l) => { try { return l ? [JSON.parse(l)] : [] } catch { return [] } })
  const since = now - WINDOW_DAYS * 864e5
  const faultDays = new Map<number, Set<string>>()
  events.forEach((e, i) => {
    if (e.type !== 'failover' || !e.from || !e.to || Date.parse(e.at) < since) return
    const at = Date.parse(e.at)
    const trouble = (o: Event) => TROUBLE.has(o.type) && (o.type === 'problem' || (o.type === 'failover' ? o.from : o.id) === e.to)
    for (let j = i + 1; j < events.length && Date.parse(events[j].at) <= at + STEADY_MS; j++) if (trouble(events[j])) return
    for (let j = i - 1; j >= 0 && Date.parse(events[j].at) >= at - STEADY_MS; j--) if (trouble(events[j])) return
    const days = faultDays.get(e.from) ?? faultDays.set(e.from, new Set()).get(e.from)!
    days.add(e.at.slice(0, 10))
  })
  return new Set([...faultDays].filter(([, days]) => days.size >= 2).map(([id]) => id))
}

const log = jsonFile(eventsFile, () => new Set<number>(), (text) => flakyFeeds(text))

/** 1 for a feed the provider keeps letting down (see above), else 0: sorts it after its steady siblings. */
export const flakyRank = (id: number): number => (log.read().has(id) ? 1 : 0)
