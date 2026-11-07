import { afterEach, expect, spyOn, test } from 'bun:test'
import { mkdtempSync, readFileSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { accessToken, connect, logPlay, refreshAt, resetTrakt, status, type Token } from './trakt'

const env = { ...process.env }
afterEach(() => {
  process.env = { ...env }
  resetTrakt()
})

const tokenFile = () => (process.env.TRAKT_TOKEN_FILE = join(mkdtempSync(join(tmpdir(), 'trakt-')), 'token.json'))
const mockTrakt = (answer: (path: string, body: any) => Response) =>
  spyOn(globalThis, 'fetch').mockImplementation((async (url: string, init?: RequestInit) =>
    answer(new URL(url).pathname, init?.body ? JSON.parse(String(init.body)) : null)) as unknown as typeof globalThis.fetch)

test('a token with expires_in 86400 is refreshed before it expires, and the rotated refresh token is saved', async () => {
  const file = tokenFile()
  const now = Math.floor(Date.now() / 1000)
  const fetch = mockTrakt((path, body) => {
    if (path === '/oauth/device/code') return Response.json({ device_code: 'D', user_code: 'ABCD1234', verification_url: 'https://auth.trakt.tv/activate', expires_in: 600, interval: 0 })
    if (path === '/oauth/device/token') return Response.json({ access_token: 'A1', refresh_token: 'R1', expires_in: 86400, created_at: now })
    if (path === '/users/settings') return Response.json({ user: { username: 'mo' } })
    if (path === '/oauth/token') {
      expect(body).toMatchObject({ refresh_token: 'R1', grant_type: 'refresh_token' })
      expect(body.client_secret).toBeUndefined() // a public client has none
      return Response.json({ access_token: 'A2', refresh_token: 'R2', expires_in: 86400, created_at: now + 18 * 3600 })
    }
    return new Response('', { status: 404 })
  })
  try {
    expect(await connect()).toEqual({ state: 'waiting', code: 'ABCD1234', url: 'https://auth.trakt.tv/activate' })
    while ((await status()).state === 'waiting') await Bun.sleep(5)
    expect(await status()).toEqual({ state: 'on', user: 'mo' })
    expect(statSync(file).mode & 0o777).toBe(0o600)
    const t = JSON.parse(readFileSync(file, 'utf8')) as Token
    expect(t.expires).toBe((now + 86400) * 1000)
    expect(refreshAt(t)).toBeLessThan(t.expires)
    expect(refreshAt(t)).toBeGreaterThan(Date.now())
    expect(await accessToken()).toBe('A1') // not due yet: no refresh
    expect(await accessToken(refreshAt(t))).toBe('A2')
    expect(JSON.parse(readFileSync(file, 'utf8'))).toMatchObject({ access: 'A2', refresh: 'R2', user: 'mo' })
    expect(fetch.mock.calls.filter(([u]) => String(u).endsWith('/oauth/token'))).toHaveLength(1)
  } finally {
    fetch.mockRestore()
  }
})

test('a play Trakt already has counts as logged', async () => {
  const file = tokenFile()
  await Bun.write(file, JSON.stringify({ access: 'A', refresh: 'R', at: Date.now(), expires: Date.now() + 864e5 }))
  let answer = Response.json({ added: { movies: 0, episodes: 0 }, not_found: { movies: [] } })
  const fetch = mockTrakt((path, body) => {
    expect(path).toBe('/sync/history')
    expect(body).toEqual({ shows: [{ ids: { tmdb: 1396 }, seasons: [{ number: 1, episodes: [{ number: 2, watched_at: '2026-11-05T20:00:00.000Z' }] }] }] })
    return answer
  })
  try {
    const at = Date.parse('2026-11-05T20:00:00Z')
    expect(await logPlay('tv:1396:s1e2', at)).toBe('ok')
    answer = new Response('', { status: 409 })
    expect(await logPlay('tv:1396:s1e2', at)).toBe('ok')
    answer = new Response('', { status: 503 })
    expect(await logPlay('tv:1396:s1e2', at)).toBe('error')
  } finally {
    fetch.mockRestore()
  }
})
