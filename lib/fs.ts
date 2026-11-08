import { readFileSync, statSync } from 'node:fs'
import { mkdir, rename, rm, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'

const writes = new Map<string, Promise<void>>()

/**
 * Writes [data] (a string as is, anything else as JSON) to [file] atomically (temp + rename), one write at a
 * time per file, so a crash or a second writer never leaves a half-written file. [mode] makes it owner-only (0o600).
 */
export function writeAtomic(file: string, data: unknown, mode?: number): Promise<void> {
  const next = (writes.get(file) ?? Promise.resolve()).catch(() => {}).then(async () => {
    await mkdir(dirname(file), { recursive: true, ...(mode && { mode: 0o700 }) })
    // A temp name of its own, so another process writing the same file (the nightly scripts) never shares it.
    const tmp = `${file}.${process.pid}.${Math.random().toString(36).slice(2)}.tmp`
    try {
      await writeFile(tmp, typeof data === 'string' ? data : JSON.stringify(data), { mode })
      await rename(tmp, file)
    } catch (e) {
      await rm(tmp, { force: true })
      throw e
    }
  })
  writes.set(file, next)
  const done = () => void (writes.get(file) === next && writes.delete(file))
  next.then(done, done)
  return next
}

/**
 * A JSON data file kept in memory and read again when it changes on disk (looked at once a second
 * at most, or at once with `read(true)`), so another process's write takes effect without a
 * restart. A missing file reads as [empty]. [path] is a function so tests can point it elsewhere;
 * [parse] turns the file's text into the data held (JSON by default).
 */
export function jsonFile<T>(path: () => string, empty: () => T, parse: (text: string) => T = JSON.parse) {
  let held: { file: string; version: string; checked: number; data: T } | null = null
  // An atomic write is a rename, so a new inode, whatever the clock says.
  const versionOf = (file: string) => {
    const st = statSync(file, { throwIfNoEntry: false })
    return st ? `${st.ino}:${st.mtimeMs}` : ''
  }
  return {
    read(now = false): T {
      const file = path()
      const t = Date.now()
      if (held?.file === file && !now && t - held.checked < 1000) return held.data
      const version = versionOf(file)
      if (held?.file !== file || held.version !== version) {
        held = { file, version, checked: t, data: version ? parse(readFileSync(file, 'utf8')) : empty() }
      } else held.checked = t
      return held.data
    },
    // The held copy's version is left as it was, so the next read loads the file again: another
    // process's write that landed around this one is picked up, never masked by ours.
    write: (data: T): Promise<void> => writeAtomic(path(), data),
  }
}
