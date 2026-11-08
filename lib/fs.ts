import { readFileSync, statSync, writeFileSync } from 'node:fs'
import { mkdir, rename, rm, stat, writeFile } from 'node:fs/promises'
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

// A crashed holder's lock is taken over after this; an update holds it for milliseconds.
const LOCK_STALE_MS = 30_000

/** Runs [fn] holding [file]'s lock (`<file>.lock`, created exclusively), which every process updating the file takes. */
async function locked<R>(file: string, fn: () => Promise<R>): Promise<R> {
  const lock = `${file}.lock`
  await mkdir(dirname(file), { recursive: true })
  for (;;) {
    try {
      await writeFile(lock, String(process.pid), { flag: 'wx' })
      break
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code !== 'EEXIST') throw e
      const st = await stat(lock).catch(() => null)
      if (st && Date.now() - st.mtimeMs > LOCK_STALE_MS) await rm(lock, { force: true })
      else await Bun.sleep(5)
    }
  }
  try {
    return await fn()
  } finally {
    await rm(lock, { force: true })
  }
}

/**
 * A JSON data file kept in memory and read again when it changes on disk (looked at once a second
 * at most, or at once with `read(true)`), so another process's write takes effect without a
 * restart. [path] is a function so tests can point it elsewhere; [parse] turns the file's text into
 * the data held (JSON by default).
 *
 * The same for every caller: a missing file reads as [empty]; so does a malformed one, after its text
 * is copied to `<file>.malformed` (the next update writes a good file over it, nothing is lost).
 * Changes go through update(), which re-reads, applies and writes under a lock every process takes,
 * so the server and the nightly scripts writing one file never drop each other's changes.
 */
export function jsonFile<T>(path: () => string, empty: () => T, parse: (text: string) => T = JSON.parse) {
  let held: { file: string; version: string; checked: number; data: T } | null = null
  // An atomic write is a rename, so a new inode, whatever the clock says.
  const versionOf = (file: string) => {
    const st = statSync(file, { throwIfNoEntry: false })
    return st ? `${st.ino}:${st.mtimeMs}` : ''
  }
  const load = (file: string, version: string): T => {
    if (!version) return empty()
    const text = readFileSync(file, 'utf8')
    try {
      return parse(text)
    } catch (e) {
      console.warn(`${file} is malformed (${e}): read as missing, its text kept as ${file}.malformed`)
      writeFileSync(`${file}.malformed`, text)
      return empty()
    }
  }
  const read = (now = false): T => {
    const file = path()
    const t = Date.now()
    if (held?.file === file && !now && t - held.checked < 1000) return held.data
    const version = versionOf(file)
    if (held?.file !== file || held.version !== version) held = { file, version, checked: t, data: load(file, version) }
    else held.checked = t
    return held.data
  }
  return {
    read,
    /** Writes [change] of the file as it is on disk now; a change answering its input unchanged writes nothing. Answers the data kept. */
    update: (change: (cur: T) => T): Promise<T> => {
      const file = path()
      return locked(file, async () => {
        const cur = read(true)
        const next = change(cur)
        if (next === cur) return cur
        await writeAtomic(file, next)
        // The file is ours until the lock goes, so the held copy is exactly what is on disk.
        held = { file, version: versionOf(file), checked: Date.now(), data: next }
        return next
      })
    },
  }
}
