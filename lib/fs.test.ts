import { afterEach, expect, test } from 'bun:test'
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { jsonFile } from './fs.js'

const dir = mkdtempSync(`${tmpdir()}/sofa-fs-`)
afterEach(() => rmSync(dir, { recursive: true, force: true }))

test('two processes updating one file at once lose nothing', async () => {
  const file = `${dir}/kept.json`
  // each process adds its own 40 keys, one update at a time, interleaving with the other's
  const writer = (name: string) => Bun.spawn(['bun', '-e', `
    const { jsonFile } = await import(${JSON.stringify(`${import.meta.dir}/fs.ts`)})
    const kept = jsonFile(() => ${JSON.stringify(file)}, () => ({}))
    for (let i = 0; i < 40; i++) await kept.update((cur) => ({ ...cur, ['${name}' + i]: i }))
  `], { stderr: 'inherit' })
  const procs = [writer('a'), writer('b')]
  expect(await Promise.all(procs.map((p) => p.exited))).toEqual([0, 0])
  expect(Object.keys(JSON.parse(readFileSync(file, 'utf8')))).toHaveLength(80)
})

test("a held copy sees another process's write; a malformed file reads as missing and is kept aside", async () => {
  const file = `${dir}/kept.json`
  const kept = jsonFile<Record<string, number>>(() => file, () => ({}))
  await kept.update((cur) => ({ ...cur, ours: 1 }))
  writeFileSync(file, JSON.stringify({ ours: 1, theirs: 2 })) // the nightly script's write
  expect(kept.read(true)).toEqual({ ours: 1, theirs: 2 })
  writeFileSync(file, '{"ours": 1, "the')
  expect(kept.read(true)).toEqual({})
  expect(readFileSync(`${file}.malformed`, 'utf8')).toBe('{"ours": 1, "the')
  await kept.update((cur) => ({ ...cur, fresh: 3 }))
  expect(kept.read(true)).toEqual({ fresh: 3 })
  expect(existsSync(`${file}.lock`)).toBe(false)
})
