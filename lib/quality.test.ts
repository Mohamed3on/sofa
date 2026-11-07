import { afterEach, beforeEach, describe, expect, test } from 'bun:test'
import { rm } from 'node:fs/promises'
import { addBitrate, allReadings, medianMbps, mergeAppReading, qualityOf, recordBitrate, recordProbed, recordReading } from './quality.js'
import { handle } from '../server.js'

const post = (route: string, body: unknown) =>
  handle(new Request(`http://localhost/api/app/${route}?t=app`, { method: 'POST', body: JSON.stringify(body) }))

const today = '2026-11-01'
const fresh = { w: 1920, h: 1080, fps: 25, codec: 'h264', name: 'A', at: '2026-10-30' }
const reading = (over: object = {}) => ({ w: 1920, h: 1080, fps: 50, codec: 'h264', name: 'A', ...over })

describe('app quality readings merge like the probe', () => {
  test('a missing or dead reading is filled', () => {
    expect(mergeAppReading(undefined, reading(), today)).toEqual({ ...reading(), at: today })
    expect(mergeAppReading(null, reading(), today)).toEqual({ ...reading(), at: today })
  })
  test('a reading older than 14 days is replaced, even by a lower one', () => {
    const old = { ...fresh, h: 2160, w: 3840, at: '2026-10-01' }
    expect(mergeAppReading(old, reading({ fps: 25 }), today)).toEqual({ ...reading({ fps: 25 }), at: today })
  })
  test('a fresh reading is upgraded at once by a taller picture, more fps or newly found Atmos', () => {
    expect(mergeAppReading(fresh, reading({ w: 3840, h: 2160 }), today)?.h).toBe(2160)
    expect(mergeAppReading(fresh, reading({ fps: 50 }), today)?.fps).toBe(50)
    expect(mergeAppReading(fresh, reading({ fps: 25, atmos: true }), today)?.atmos).toBe(true)
  })
  test('audio breaks a picture tie: 5.1 beats stereo, Atmos beats 5.1', () => {
    expect(mergeAppReading(fresh, reading({ fps: 25, surround: true }), today)?.surround).toBe(true)
    expect(mergeAppReading({ ...fresh, atmos: true }, reading({ fps: 25, surround: true }), today)).toBeNull()
  })
  test('fewer fps lowers a fresh reading only when seen on two different days, keeping Atmos', () => {
    const p50 = { ...fresh, fps: 50, atmos: true as const }
    const first = mergeAppReading(p50, reading({ fps: 25 }), today)
    expect(first).toMatchObject({ fps: 50, low: today })
    expect(mergeAppReading(first, reading({ fps: 25 }), '2026-11-02')).toMatchObject({ fps: 25, atmos: true, at: '2026-11-02' })
  })
  test('a shorter picture lowers a fresh reading only when seen on two different days', () => {
    const lower = reading({ h: 720, w: 1280 })
    const first = mergeAppReading(fresh, lower, today)
    expect(first).toMatchObject({ h: 1080, low: today })
    expect(mergeAppReading(first, lower, today)).toBeNull()
    expect(mergeAppReading(first, lower, '2026-11-02')).toMatchObject({ h: 720, at: '2026-11-02' })
    expect(mergeAppReading(first, reading({ fps: 25 }), '2026-11-02')).toEqual(fresh)
  })
  test('a replaced reading keeps the bitrates measured on the feed', () => {
    expect(mergeAppReading({ ...fresh, mbps: [6.1] }, reading({ w: 3840, h: 2160 }), today)?.mbps).toEqual([6.1])
  })
})

describe('feed bitrates are the median of recent viewing', () => {
  test('the last five readings count, so one odd reading does not move it', () => {
    let m = addBitrate(fresh, 30)
    for (const r of [6.04, 5.9, 6.3, 6.1, 14]) m = addBitrate(m, r)
    expect(m?.mbps).toEqual([6, 5.9, 6.3, 6.1, 14])
    expect(medianMbps(m)).toBe(6.1)
    expect(medianMbps(fresh)).toBeUndefined()
  })
})

describe('the store', () => {
  const env = { ...process.env }
  const dir = `${import.meta.dir}/../.cache/test-quality-store`
  beforeEach(async () => {
    process.env.QUALITY_FILE = `${dir}/quality.json`
    await rm(dir, { recursive: true, force: true })
  })
  afterEach(async () => {
    process.env = { ...env }
    await rm(dir, { recursive: true, force: true })
  })

  test('a reading the app records while the probe runs is still there after its merge', async () => {
    await recordReading(1, reading({ fps: 25 }), '2026-10-01')
    await recordReading(2, reading(), '2026-10-01')
    const copy = allReadings() // the probe starts on a copy
    await recordReading(1, reading({ w: 3840, h: 2160 }), today) // the stick, mid-run
    await recordBitrate(2, 6.2)
    const probed = { 1: { ...copy[1]!, fps: 50, at: today }, 2: { ...reading({ fps: 25 }), at: today }, 3: { ...reading(), at: today } }
    expect(await recordProbed(probed, today)).toBe(2)
    expect(qualityOf(1)).toMatchObject({ h: 2160, at: today })
    expect(qualityOf(2)).toMatchObject({ fps: 25, mbps: [6.2] })
    expect(qualityOf(3)).toMatchObject({ h: 1080 })
  })
  test('picks up a write by another process without a restart', async () => {
    expect(qualityOf(4)).toBeUndefined()
    await Bun.write(`${dir}/quality.json`, JSON.stringify({ 4: { ...reading(), at: today } }))
    await Bun.sleep(1100)
    expect(qualityOf(4)).toMatchObject({ h: 1080 })
  })
})

describe('/api/app/quality', () => {
  const env = { ...process.env }
  const dir = `${import.meta.dir}/../.cache/test-quality`
  beforeEach(async () => {
    Object.assign(process.env, { APP_TOKENS: 'app', QUALITY_FILE: `${dir}/quality.json` })
    await rm(dir, { recursive: true, force: true })
  })
  afterEach(async () => {
    process.env = { ...env }
    await rm(dir, { recursive: true, force: true })
  })

  test('refuses a malformed reading', async () => {
    for (const bad of [{ id: 'x', ...reading() }, { id: 990001, ...reading({ h: 99999 }) }, { id: 990001, ...reading({ codec: '<b>' }) }])
      expect((await post('quality', bad)).status).toBe(400)
  })
  test('stores a new reading in the store', async () => {
    const res = await post('quality', { id: 990001, ...reading() })
    expect(await res.json()).toEqual({ stored: true })
    expect(qualityOf(990001)).toMatchObject({ h: 1080, fps: 50 })
    expect((await Bun.file(`${dir}/quality.json`).json())['990001']).toMatchObject({ w: 1920, h: 1080, fps: 50 })
    expect(await (await post('quality', { id: 990001, ...reading({ w: 3840, h: 2160, fps: 25 }) })).json()).toEqual({ stored: true })
    expect(await (await post('quality', { id: 990001, ...reading({ w: 3840, h: 2160, fps: 25 }) })).json()).toEqual({ stored: false })
  })
})

describe('/api/app/bitrate', () => {
  const env = { ...process.env }
  const dir = `${import.meta.dir}/../.cache/test-bitrate`
  beforeEach(async () => {
    Object.assign(process.env, { APP_TOKENS: 'app', QUALITY_FILE: `${dir}/quality.json` })
    await rm(dir, { recursive: true, force: true })
  })
  afterEach(async () => {
    process.env = { ...env }
    await rm(dir, { recursive: true, force: true })
  })

  test('adds a measured bitrate to the feed and refuses nonsense', async () => {
    await recordReading(990002, reading(), today)
    for (const bad of [{ id: 990002, mbps: 0.04 }, { id: 990002, mbps: 'x' }, { id: 990002, mbps: 999 }])
      expect((await post('bitrate', bad)).status).toBe(400)
    expect(await (await post('bitrate', { id: 990002, mbps: 6.2 })).json()).toEqual({ stored: true })
    expect(qualityOf(990002)?.mbps).toEqual([6.2])
    expect((await Bun.file(`${dir}/quality.json`).json())['990002'].mbps).toEqual([6.2])
  })
})

describe('/api/app/events', () => {
  const env = { ...process.env }
  const file = `${import.meta.dir}/../.cache/test-events/app-events.jsonl`
  beforeEach(async () => {
    Object.assign(process.env, { APP_TOKENS: 'app', APP_EVENTS_FILE: file })
    await rm(`${import.meta.dir}/../.cache/test-events`, { recursive: true, force: true })
  })
  afterEach(() => { process.env = { ...env } })

  test('appends a valid batch with the time received, and refuses the rest', async () => {
    expect((await post('events', [{ type: 'nope' }])).status).toBe(400)
    expect((await post('events', [{ type: 'tune', id: 1, ms: 900 }, { type: 'stall', id: 1 }, { type: 'rebuffer', id: 1, netMbps: 4.1, streamMbps: 6.2, cushionMs: 3000 }, { type: 'trial', copy: 'rd:a', works: false }])).status).toBe(204)
    const lines = (await Bun.file(file).text()).trim().split('\n').map((l) => JSON.parse(l))
    expect(lines.map((l) => [l.type, typeof l.at])).toEqual([['tune', 'string'], ['stall', 'string'], ['rebuffer', 'string'], ['trial', 'string']])
  })
})
