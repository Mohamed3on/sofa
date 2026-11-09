// The server the app's profiling builds talk to on an emulator (http://10.0.2.2:8791): today's real
// channels and guide, fetched once from the real server, with the provider route pointed back here,
// where every channel plays the same generated test clip. So the emulator plays (the health sampling,
// info view and previews run) while no stream ever reaches the provider (the account has one stream
// slot, and the stick may be using it), and nothing the emulator does reaches the box's event log.
// Usage: bun firetv/baselineprofile/fixture-server.ts  (needs ffmpeg; reads IPTV_SERVER_URL /
// IPTV_APP_TOKEN from the environment or ~/.gradle/gradle.properties, like the Gradle build).
import { homedir } from 'node:os'
import { join } from 'node:path'
import { build, MODES, type Mode } from './worst-case.ts'

const PORT = 8791
const SELF = `http://10.0.2.2:${PORT}` // this server, as the emulator reaches its host

// A minute of 720p50 H.264 + AAC in MPEG-TS, like a provider's live feed; built once into build/.
const clip = join(import.meta.dir, 'build', 'fixture-clip.ts')
if (!(await Bun.file(clip).exists())) {
  const ffmpeg = Bun.spawnSync([
    'ffmpeg', '-loglevel', 'error', '-y',
    '-f', 'lavfi', '-i', 'testsrc2=size=1280x720:rate=50', '-f', 'lavfi', '-i', 'sine=frequency=440',
    '-t', '60', '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '30', '-g', '50', '-pix_fmt', 'yuv420p',
    '-c:a', 'aac', '-f', 'mpegts', clip,
  ])
  if (!ffmpeg.success) throw new Error(`ffmpeg failed: ${ffmpeg.stderr}`)
}

async function secret(name: string): Promise<string> {
  if (process.env[name]) return process.env[name]!
  const props = await Bun.file(join(homedir(), '.gradle', 'gradle.properties')).text().catch(() => '')
  const line = props.split('\n').find((l) => l.startsWith(`${name}=`))
  if (!line) throw new Error(`${name} is not set (env or ~/.gradle/gradle.properties)`)
  return line.slice(name.length + 1).trim()
}

const base = await secret('IPTV_SERVER_URL')
const token = await secret('IPTV_APP_TOKEN')
const get = async (kind: string) => {
  const res = await fetch(`${base}/api/app/${kind}?t=${encodeURIComponent(token)}`)
  if (!res.ok) throw new Error(`${kind}: HTTP ${res.status}`)
  return res
}

const feed = (await (await get('channels')).json()) as { route: Record<string, unknown> }
feed.route = { ...feed.route, plain: SELF, vpn: SELF, user: 'x', pass: 'x' }
const guideDb = join(import.meta.dir, 'build', 'fixture-guide.db')
await Bun.write(guideDb, Bun.gunzipSync(new Uint8Array(await (await get('guide')).arrayBuffer())))

// The data the app is served, switched with GET /data/<mode> (worst-case.ts, for break-ui). Clear the app's data after
// a switch (data.sh does both): it keeps a fresh guide for hours.
let channels = '', guide = new Uint8Array(), scores = '{}'
function use(mode: Mode) {
  const built = build(mode, feed as never, guideDb, join(import.meta.dir, 'build'))
  channels = JSON.stringify(built.feed)
  guide = built.guide
  scores = JSON.stringify(built.scores)
}
use((process.env.DATA as Mode | undefined) ?? 'demo')

Bun.serve({
  port: PORT,
  hostname: '127.0.0.1',
  fetch(req) {
    const { pathname } = new URL(req.url)
    if (req.method === 'POST') return new Response(null, { status: 204 }) // events, quality readings
    const mode = pathname.match(/^\/data\/(\w+)$/)?.[1] as Mode | undefined
    if (mode && MODES.includes(mode)) return use(mode), new Response(`${mode}\n`)
    if (pathname === '/api/app/matches') return new Response(scores, { headers: { 'Content-Type': 'application/json' } })
    if (pathname === '/api/app/channels') return new Response(channels, { headers: { 'Content-Type': 'application/json' } })
    if (pathname === '/api/app/guide') return new Response(guide, { headers: { 'Content-Type': 'application/gzip' } })
    if (pathname.startsWith('/live/')) return new Response(Bun.file(clip), { headers: { 'Content-Type': 'video/mp2t' } })
    return new Response('Not found', { status: 404 }) // version, apk: no update
  },
})
console.log(`fixture server on :${PORT} (${channels.length} B channels, ${guide.length} B guide)`)
