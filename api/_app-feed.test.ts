import { afterEach, beforeEach, describe, expect, setSystemTime, spyOn, test } from 'bun:test'
import { buildAppFeed, buildAppGuide, guideDescr } from './_app-feed.js'
import { buildM3U, withBackupFeeds, withCompetitionGroups, type Channel } from './_lib.js'
import { BACKUP_ID, VPN_HOST } from './_provider.js'
import { Database } from 'bun:sqlite'
import { rm } from 'node:fs/promises'
import { channels as channelsRoute, guide as guideRoute } from './app.js'
import epg from './epg.js'
import { handle } from '../server.js'

const cfg = { host: 'http://plain.example', user: 'u', pass: 'p' }
const UK = '🏴 Sky Sports & UK Sports'
const ch = (streamId: number, name: string, tvgId: string, extra: Partial<Channel> = {}): Channel =>
  ({ streamId, name, logo: '', tvgId, group: UK, isEventSlot: false, q: 4, ...extra })
const byId = (feed: ReturnType<typeof buildAppFeed>, id: number) => feed.channels.filter((c) => c.id === id)

describe('app channel feed', () => {
  test('a 🏆 copy shares its streamId and points home to the original group', () => {
    const grouped = withCompetitionGroups([ch(900001, 'UK: SKY SPORTS PREMIER LEAGUE HD', 'test.home', { comps: ['pl'] })])
    const feed = buildAppFeed(cfg, grouped)
    const rows = byId(feed, 900001)
    expect(rows.map((r) => r.group)).toEqual(['🏆 Premier League', UK])
    expect(rows.every((r) => r.home === UK)).toBe(true)
    expect(rows[0].family).toBe(rows[1].family)
    expect(feed.groups).toEqual(['🏆 Premier League', UK])
  })

  test('a Premier League abroad channel appears only in its 🏆 group, with no home group', () => {
    const abroad = ch(900002, 'NZ: SKY SPORT PREMIER LEAGUE', 'test.nz', { group: '🌏 Premier League abroad', comps: ['pl'] })
    const feed = buildAppFeed(cfg, withCompetitionGroups([abroad]))
    const rows = byId(feed, 900002)
    expect(rows.map((r) => r.group)).toEqual(['🏆 Premier League'])
    expect(rows[0].home).toBeUndefined()
  })

  test('feeds of one channel share a family, ranked in server order; a mislabelled id stays out', () => {
    const feed = buildAppFeed(cfg, [
      ch(1, 'VIP: TNT SPORTS 1 RAW', 'TNTSport1.uk'),
      ch(2, 'NOW: TNT SPORT 1 HD', 'tntsports1.uk', { q: 3 }),
      ch(3, 'UK: SKY SPORTS NEWS HD', 'SkySportsNews.uk'),
      ch(4, 'UK: SKY SPORTS MAIN EVENT HD', 'SkySportsNews.uk'),
    ])
    const [tnt1, tnt2, news, mainEvent] = [1, 2, 3, 4].map((id) => byId(feed, id)[0])
    expect(tnt1.family).toBe(tnt2.family)
    expect([tnt1.rank, tnt2.rank]).toEqual([0, 1])
    expect(mainEvent.family).not.toBe(news.family)
    expect(tnt2.q).toBe(3)
  })

  test("the backup provider's copy joins its channel's family last, on its own front and account", async () => {
    const backup = { host: 'http://b.example', vpnHost: 'http://bvpn.example', user: 'bu', pass: 'bp' }
    const fetchSpy = spyOn(globalThis, 'fetch').mockImplementation((async (url: string) => {
      const action = new URL(url).searchParams.get('action')
      if (action === 'get_live_categories') return Response.json([{ category_id: '7', category_name: 'UK| SPORTS' }, { category_id: '8', category_name: 'UK| PPV EVENTS' }])
      return Response.json([
        { stream_id: 50, name: 'UK: TNT SPORTS 1 FHD', category_id: '7' },
        { stream_id: 51, name: 'UK: TNT SPORTS 1 HD', category_id: '7' }, // a lower tier of the same channel
        { stream_id: 52, name: 'UK: SKY SPORTS F1 HD', category_id: '7' }, // not in A's list
        { stream_id: 53, name: 'UK: TNT SPORTS 1', category_id: '8' }, // an event slot
      ])
    }) as typeof fetch)
    try {
      const merged = await withBackupFeeds([ch(1, 'VIP: TNT SPORTS 1 RAW', 'TNTSport1.uk'), ch(2, 'NOW: TNT SPORT 1 HD', 'tntsports1.uk', { q: 3 }), ch(3, 'UK: SKY SPORTS NEWS HD', 'SkySportsNews.uk')], backup)
      expect(merged.map((c) => c.streamId)).toEqual([1, 2, BACKUP_ID + 50, 3])
      const feed = buildAppFeed(cfg, merged, backup)
      const b = byId(feed, BACKUP_ID + 50)[0]
      expect([b.family, b.rank, b.tvgId, b.group]).toEqual([byId(feed, 1)[0].family, 2, 'tntsports1.uk', UK])
      expect(feed.backup).toEqual({ plain: 'http://b.example', vpn: 'http://bvpn.example', user: 'bu', pass: 'bp' })
      expect(buildM3U(cfg, merged, [], backup).split('\n')[6]).toBe('http://b.example/live/bu/bp/50.ts')
    } finally {
      fetchSpy.mockRestore()
    }
  })

  test('buildM3U output for the same channels is unchanged by the VPN_HOST move', () => {
    const m3u = buildM3U({ ...cfg, host: VPN_HOST }, [ch(1, 'UK: A', 'a.uk')], ['http://e'])
    expect(m3u).toBe(
      '#EXTM3U url-tvg="http://e"\n#EXTINF:-1 tvg-id="a.uk" tvg-name="UK: A" tvg-logo="" group-title="🏴 Sky Sports & UK Sports",UK: A\n' +
        'http://apps.g1ming-hub.sbs/live/u/p/1.ts\n',
    )
  })
})

describe('/api/app/channels token gate', () => {
  const env = { ...process.env }
  // Provider API stub: one category with one stream.
  beforeEach(() => {
    Object.assign(process.env, { XTREAM_HOST: 'http://plain.example', XTREAM_USER: 'u', XTREAM_PASS: 'p', PLAYLIST_TOKEN: 'tv' })
    spyOn(globalThis, 'fetch').mockImplementation((async (url: string) => {
      const action = new URL(url).searchParams.get('action')
      if (action === 'get_live_categories') return Response.json([{ category_id: '9001', category_name: 'UK| PREMIER LEAGUE' }])
      if (action === 'get_live_streams')
        return Response.json([{ stream_id: 1, name: 'UK: SKY SPORTS MAIN EVENT FHD', epg_channel_id: 'a.uk', category_id: '9001' }])
      return Response.json([])
    }) as typeof fetch)
  })
  afterEach(() => {
    process.env = { ...env }
  })
  const get = (t?: string) => channelsRoute(new Request(`http://localhost/api/app/channels${t === undefined ? '' : `?t=${t}`}`))

  test('no token, the TiviMate token, an empty token, or no APP_TOKENS are refused', async () => {
    process.env.APP_TOKENS = 'new,'
    expect((await get()).status).toBe(403)
    expect((await get('tv')).status).toBe(403)
    expect((await get('')).status).toBe(403)
    delete process.env.APP_TOKENS
    expect((await get('new')).status).toBe(403)
  })

  test('either of two app tokens returns the private feed', async () => {
    process.env.APP_TOKENS = 'old, new'
    for (const t of ['old', 'new']) {
      const res = await get(t)
      expect(res.status).toBe(200)
      expect(res.headers.get('cache-control')).toBe('private, s-maxage=1800, stale-while-revalidate=3600')
      expect(res.headers.get('access-control-allow-origin')).toBeNull()
      expect((await res.json()).channels.length).toBeGreaterThan(0)
    }
  })

  test('through server.ts, a warmed cache never serves the tokenless path', async () => {
    process.env.APP_TOKENS = 'new'
    expect((await handle(new Request('http://localhost/api/app/channels?t=new'))).status).toBe(200)
    expect((await handle(new Request('http://localhost/api/app/channels'))).status).toBe(403)
    expect((await handle(new Request('http://localhost/api/app/channels?t='))).status).toBe(403)
  })
})

// A fixed provider: a PPV slot, a channel with real programmes, and an uncovered one.
const NOW = new Date('2026-11-01T12:30:00Z')
const stamp = (h: number) => {
  const d = new Date(NOW.getTime() + h * 3600_000)
  return d.toISOString().replace(/[-:T]/g, '').replace(/\.\d+Z$/, '') + ' +0000'
}
const prog = (id: string, from: number, to: number, title: string) =>
  `<programme start="${stamp(from)}" stop="${stamp(to)}" channel="${id}"><title>${title}</title><desc>d</desc></programme>`
const PROVIDER_XML = `<tv>${[
  prog('a.uk', -4, -3, 'Old News'),
  prog('a.uk', -2, -1, 'Recent News'),
  prog('a.uk', -0.5, 1.5, 'Bayern München v Arsenal'),
  prog('a.uk', 1.5, 2.5, 'Sky Sports News'),
].join('')}</tv>`
function stubProvider() {
  Object.assign(process.env, { XTREAM_HOST: 'http://plain.example', XTREAM_USER: 'u', XTREAM_PASS: 'p', PLAYLIST_TOKEN: 'tv', EPG_URLS: '' })
  spyOn(globalThis, 'fetch').mockImplementation((async (url: string) => {
    if (url.includes('xmltv.php')) return new Response(PROVIDER_XML)
    const action = new URL(url).searchParams.get('action')
    if (action === 'get_live_categories')
      return Response.json([{ category_id: '9001', category_name: 'UK| PREMIER LEAGUE' }, { category_id: '9002', category_name: 'UK| PREMIER LEAGUE PPV' }])
    if (action === 'get_live_streams')
      return Response.json([
        { stream_id: 1, name: 'UK: SKY SPORTS MAIN EVENT FHD', epg_channel_id: 'a.uk', category_id: '9001' },
        { stream_id: 2, name: 'UK: SKY SPORTS ACTION FHD', epg_channel_id: 'b.uk', category_id: '9001' },
        { stream_id: 3, name: 'UK PPV 1: Arsenal v Chelsea', epg_channel_id: null, category_id: '9002' },
      ])
    return Response.json([])
  }) as typeof fetch)
}

describe('guide', () => {
  const env = { ...process.env }
  beforeEach(() => {
    setSystemTime(NOW)
    stubProvider()
  })
  afterEach(() => {
    setSystemTime()
    process.env = { ...env }
  })

  test('/api/epg XML for a fixed provider is unchanged', async () => {
    const res = await epg(new Request('http://localhost/api/epg?t=tv'))
    expect(res.status).toBe(200)
    expect(await res.text()).toMatchSnapshot()
  })

  test("/api/app/guide needs an app token and is private", async () => {
    process.env.APP_TOKENS = "app"
    expect((await guideRoute(new Request("http://localhost/api/app/guide?t=tv"))).status).toBe(403)
    const res = await guideRoute(new Request("http://localhost/api/app/guide?t=app"))
    expect(res.status).toBe(200)
    expect(res.headers.get("cache-control")).toBe("private, s-maxage=900")
    expect(res.headers.get("access-control-allow-origin")).toBeNull()
  })

  test("/api/app/guide is a gzip SQLite file of the same guide, flagged for the app", async () => {
    process.env.APP_TOKENS = "app"
    const res = await guideRoute(new Request("http://localhost/api/app/guide?t=app"))
    const db = Database.deserialize(Bun.gunzipSync(new Uint8Array(await res.arrayBuffer())))
    type Row = { tvg_id: string; start: number; stop: number; title: string; norm: string; descr: string; synthetic: number; match: number }
    const rows = db.query("SELECT * FROM programmes ORDER BY tvg_id, start").all() as Row[]
    const of = (id: string) => rows.filter((r) => r.tvg_id === id)
    const titled = (t: string) => rows.find((r) => r.title === t)
    // PPV slot: 25 hourly name-as-title rows
    expect(of("ppv.3").length).toBe(25)
    expect(of("ppv.3").every((r) => r.synthetic === 1 && r.match === 1)).toBe(true)
    // uncovered regular channel gets name-as-title rows, flagged synthetic
    expect(of("b.uk").length).toBeGreaterThan(0)
    expect(of("b.uk").every((r) => r.synthetic === 1)).toBe(true)
    // real programmes
    expect(of("a.uk").every((r) => r.synthetic === 0)).toBe(true)
    expect(titled("Bayern München v Arsenal")).toMatchObject({ norm: "bayern munchen v arsenal", match: 1, synthetic: 0 })
    expect(titled("Sky Sports News")?.match).toBe(0)
    expect(titled("Bayern München v Arsenal")!.start).toBe(NOW.getTime() / 1000 - 1800)
    // a live match names its competition's 🏆 group; other titles name none
    // …and a fixture titled without one takes it from another channel's airing of the same match
    const appGuide = Database.deserialize(Bun.gunzipSync(buildAppGuide([], `<programme start="${stamp(0)}" stop="${stamp(2)}" channel="x"><title>Live Serie A: Juventus v Napoli</title></programme>` +
      `<programme start="${stamp(0)}" stop="${stamp(2)}" channel="y"><title>Juventus - SSC Napoli</title></programme>` +
      `<programme start="${stamp(0)}" stop="${stamp(2)}" channel="z"><title>Ajax - Feyenoord</title></programme>` + PROVIDER_XML)))
    expect(appGuide.query("SELECT title, comp FROM programmes WHERE comp IS NOT NULL ORDER BY tvg_id").all()).toEqual([
      { title: "Live Serie A: Juventus v Napoli", comp: "🏆 Serie A" },
      { title: "Juventus - SSC Napoli", comp: "🏆 Serie A" },
    ])
    // ended 1h ago stays, ended 3h ago is dropped
    expect(titled("Recent News")).toBeDefined()
    expect(titled("Old News")).toBeUndefined()
  })

  test('a description reads as one paragraph without what the row already says', () => {
    expect(guideDescr('Arsenal vs Hull City - English Premier League 2026/2027 - Week 10',
      String.raw`Arsenal vs Hull City\r\nAt 7-11-2026, MECCA 18:00, GMT 15:00\r\nPremier League\r\nSeason 2026/2027\r\nEmirates Stadium Stadium\r\n.`)).toBe('Emirates Stadium')
    expect(guideDescr('NBA Action', String.raw`Weekly segments \r\n\r\nInside the League.\r\nCaptain Jon Jon sails`))
      .toBe('Weekly segments · Inside the League. Captain Jon Jon sails')
  })
})

describe('app distribution routes', () => {
  const env = { ...process.env }
  const dir = `${import.meta.dir}/../.cache/test-app-dir`
  beforeEach(async () => {
    process.env.APP_TOKENS = 'app'
    process.env.APP_DIR = dir
    await rm(dir, { recursive: true, force: true })
  })
  afterEach(async () => {
    process.env = { ...env }
    await rm(dir, { recursive: true, force: true })
  })
  const get = (path: string) => handle(new Request(`http://localhost${path}`))

  test('without the token both routes refuse, even when no files exist', async () => {
    for (const p of ['/api/app/version', '/api/app/apk', '/api/app/version?t=tv']) expect((await get(p)).status).toBe(403)
  })

  test('with the token and no files, both are 404', async () => {
    for (const p of ['/api/app/version?t=app', '/api/app/apk?t=app']) expect((await get(p)).status).toBe(404)
  })

  test('version.json and the APK are served verbatim, never cached', async () => {
    const manifest = '{"versionCode":42,"versionName":"1.42","sha256":"ab"}'
    await Bun.write(`${dir}/version.json`, manifest)
    await Bun.write(`${dir}/app.apk`, 'PK-apk-bytes')
    const v = await get('/api/app/version?t=app')
    expect([v.status, await v.text(), v.headers.get('cache-control')]).toEqual([200, manifest, 'no-store'])
    const apk = await get('/api/app/apk?t=app')
    expect([apk.status, await apk.text(), apk.headers.get('cache-control')]).toEqual([200, 'PK-apk-bytes', 'no-store'])
    expect(apk.headers.get('access-control-allow-origin')).toBeNull()
    // bypasses server.ts's cache: a new build is served at once
    await Bun.write(`${dir}/version.json`, '{"versionCode":43}')
    expect(await (await get('/api/app/version?t=app')).text()).toBe('{"versionCode":43}')
  })
})
