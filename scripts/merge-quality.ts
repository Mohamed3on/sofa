// Merges a quality probe run's results (the entries it changed, written by scripts/probe-quality.ts)
// into the live store on the box, entry by entry, so readings the app recorded during the run stay.
// The server reads the store again on its own; no restart.
// Usage: bun scripts/merge-quality.ts [probed.json]  (stdin without a file)
import { recordProbed, type Readings } from '../lib/quality.ts'

const probed: Readings = await (Bun.argv[2] ? Bun.file(Bun.argv[2]) : Bun.stdin).json()
const changed = await recordProbed(probed, new Date().toISOString().slice(0, 10))
console.log(`Merged ${changed} of ${Object.keys(probed).length} probed readings into the store.`)
