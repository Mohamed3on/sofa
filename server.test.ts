import { afterEach, expect, spyOn, test } from 'bun:test'
import { rm } from 'node:fs/promises'
import { forgetBuiltGuide } from './lib/curation.js'
import { handle, routes } from './server.js'

const env = { ...process.env }
const dir = `${import.meta.dir}/.cache/test-server`
afterEach(async () => {
  process.env = { ...env }
  await rm(dir, { recursive: true, force: true })
})

test('every route refuses no token, an empty one or the other gate\'s', async () => {
  Object.assign(process.env, { PLAYLIST_TOKEN: 'tv', APP_TOKENS: 'old, new' })
  for (const [path, { gate, method = 'GET' }] of Object.entries(routes))
    for (const q of ['', '?t=', `?t=${gate === 'app' ? 'tv' : 'new'}`])
      expect([path + q, (await handle(new Request(`http://localhost${path}${q}`, { method }))).status]).toEqual([path + q, 403])
  // Both app tokens open the app gate while one rotates out.
  process.env.APP_DIR = dir
  for (const t of ['old', 'new']) expect((await handle(new Request(`http://localhost/api/app/version?t=${t}`))).status).toBe(404)
})

test('a stored quality reading makes the kept channel list stale; an unchanged one keeps it', async () => {
  Object.assign(process.env, { XTREAM_HOST: 'http://plain.example', XTREAM_USER: 'u', XTREAM_PASS: 'p', APP_TOKENS: 'inv', QUALITY_FILE: `${dir}/quality.json` })
  let builds = 0
  const fetchSpy = spyOn(globalThis, 'fetch').mockImplementation((async (url: string) => {
    const action = new URL(url).searchParams.get('action')
    if (action === 'get_live_categories') return Response.json([{ category_id: '9001', category_name: 'UK| PREMIER LEAGUE' }])
    if (action === 'get_live_streams') builds++
    return Response.json(action === 'get_live_streams' ? [{ stream_id: 1, name: 'UK: SKY SPORTS MAIN EVENT FHD', epg_channel_id: 'a.uk', category_id: '9001' }] : [])
  }) as typeof fetch)
  const channels = () => handle(new Request('http://localhost/api/app/channels?t=inv'))
  const post = async (fps: number) => (await handle(new Request('http://localhost/api/app/quality?t=inv', {
    method: 'POST', body: JSON.stringify({ id: 990003, w: 1920, h: 1080, fps, codec: 'h264', name: 'A' }),
  }))).json()
  forgetBuiltGuide()
  try {
    // the guide its 🏆 groups come from shares the list's pull
    const first = await channels()
    expect([first.status, first.headers.get('cache-control')]).toEqual([200, 'private, s-maxage=1800, stale-while-revalidate=3600'])
    await channels()
    expect(builds).toBe(1)
    expect(await post(50)).toEqual({ stored: true })
    await channels()
    expect(builds).toBe(2)
    expect(await post(50)).toEqual({ stored: false })
    await channels()
    expect(builds).toBe(2)
  } finally {
    fetchSpy.mockRestore()
  }
})
