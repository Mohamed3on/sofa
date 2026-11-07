import { afterEach, expect, spyOn, test } from 'bun:test'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { filmCopy, markInfringing, titleCopies, torrentioCandidates, type IptvCopy } from './copies'
import { flagLanguages, parseRelease } from './release-name'
import torrentio from './fixtures/torrentio-movie-tt1375666.json'
import movie from './fixtures/tmdb-movie-27205.json'
import rdList from './fixtures/torrentio-rd-movie-tt1375666.json'

const env = { ...process.env }
afterEach(() => {
  process.env = { ...env }
})

test('release names give the ranker resolution, HDR, Dolby Vision profile, codec, audio and languages', () => {
  expect(parseRelease('Movie.2019.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.TrueHD.7.1.Atmos-GRP')).toEqual({
    res: 2160, hdr: ['dv', 'hdr10'], codec: 'hevc', audio: 'truehd', ch: 8, atmos: true, langs: [], remux: true,
  })
  expect(parseRelease('Movie.2019.1080p.WEB-DL.DDP5.1.Atmos.H.264')).toEqual({ res: 1080, hdr: [], codec: 'h264', audio: 'eac3', ch: 6, atmos: true, langs: [] })
  expect(parseRelease('Movie.2019.MULTi.1080p.BluRay.x264').langs).toEqual(['multi'])
  expect(parseRelease('Movie.2019.iTALiAN.1080p.WEB-DL.x264').langs).toEqual(['it'])
  // A title word is not a tag: only what follows the year counts.
  expect(parseRelease('The.Italian.Job.2003.1080p.BluRay.x264').langs).toEqual([])
  expect(parseRelease('Movie.2019.2160p.BluRay.REMUX.DV.P7.HDR.HEVC').dvProfile).toBe(7)
  expect(parseRelease('Movie 2019 2160p UHD BluRay FEL HEVC').dvProfile).toBe(7)
  expect(parseRelease('Movie 2019 2160p ita eng AC3 5.1 sub ita eng').subs).toEqual(['it', 'en'])
})

test('Torrentio streams become candidates with audio languages from the flags, telesyncs left out', () => {
  const c = torrentioCandidates(torrentio.streams)
  expect(c).toHaveLength(4)
  expect(c[0]).toMatchObject({ hash: '2849211f150e966ef7924f43aec22727a644e10d', file: 0, res: 2160, hdr: ['hdr10'], codec: 'av1', size: Math.round(8.91 * 2 ** 30), seeds: 296 })
  expect(c[0].langs.sort()).toEqual(['de', 'en', 'es', 'fr', 'it'])
  // Sixteen flags name subtitles too (Toy Story 5's iTunes copy listed AR with English and Chinese audio only).
  expect(flagLanguages('🇬🇧 / 🇵🇹 / 🇨🇳 / 🇳🇱 / 🇵🇱 / 🇨🇿 / 🇭🇺 / 🇧🇬 / 🇭🇷 / 🇺🇦 / 🇩🇰 / 🇸🇪 / 🇳🇴 / 🇹🇷 / 🇸🇦 / 🇮🇱')).toEqual([])
  expect(flagLanguages('Multi Audio 🇬🇧 / 🇵🇹 / 🇨🇳 / 🇳🇱 / 🇵🇱 / 🇨🇿')).toEqual(['multi'])
  expect(c[3]).toMatchObject({ file: 3, filename: 'Inception.2010.1080p.BrRip.x264.YIFY.mp4', res: 1080, langs: [] })
  // The file name names the audio the release doesn't: here English only in DTS-HD.
  const [remux] = torrentioCandidates([{
    name: 'Torrentio\n4k DV | HDR',
    title: 'Начало / Inception (2010) UHD BDRemux 2160p | 4K | HDR | Dolby Vision Profile 8 | D P2 A | Лицензия\n👤 29 💾 86.15 GB ⚙️ Rutor\n🇬🇧 / 🇷🇺 / 🇺🇦',
    infoHash: '10c93a2ad69b9c576a5de6f9495b266fc992c889',
    fileIdx: 0,
    behaviorHints: { filename: 'Inception.2010.2160p.UHD.BDRemux.DTS-HD.MA.5.1.HYBRID.DoVi.P8.by.DVT.mkv' },
  }])
  expect(remux).toMatchObject({ res: 2160, hdr: ['dv', 'hdr10'], dvProfile: 8, audio: 'dts-hd', ch: 6, remux: true })
})

test('every catalogue copy of a film keeps its language: dubs name the audio, subtitled copies the original', () => {
  const copies = [
    filmCopy({ id: 1, name: '4K-EN - Inception (2010)', category: 'EN - MOVIES ⁴ᴷ ³⁸⁴⁰ᴾ', ext: 'mkv' }),
    filmCopy({ id: 2, name: 'TOP - Inception (2010)', category: 'TOP MOVIES BLURAY', ext: 'mkv' }),
    filmCopy({ id: 3, name: '4K-DE - Inception (2010)', category: 'DE - FILME ⁴ᴷ ³⁸⁴⁰ᴾ', ext: 'mkv' }),
    filmCopy({ id: 4, name: 'AR-SUBS - Inception (2010)', category: 'أفلام أجنبية قديمه', ext: 'mp4' }),
  ]
  expect(copies.map((c) => [c.id, c.res, c.langs, c.subs ?? []])).toEqual([[1, 2160, [], []], [2, 1080, [], []], [3, 2160, ['de'], []], [4, 0, [], ['ar']]])
  // The app's own reading replaces the claim.
  expect(filmCopy({ id: 1, name: '4K-EN - Inception', category: '', ext: 'mkv' }, { w: 3840, h: 1600, codec: 'hevc', hdr: 'Dolby Vision', audio: 'eac3', channels: 6, atmos: true, kbps: 21000 }))
    .toMatchObject({ basis: 'measured', res: 2160, hdr: ['dv'], codec: 'hevc', audio: 'eac3', ch: 6, atmos: true, kbps: 21000 })
})

const iptv: IptvCopy[] = [filmCopy({ id: 1, name: '4K-EN - Inception (2010)', category: 'EN - MOVIES 4K', ext: 'mkv' })]
function tmdbThen(imdb: string, torrentioAnswer: () => Promise<Response>) {
  process.env.TMDB_TOKEN = '0123456789abcdef0123456789abcdef'
  process.env.TMDB_CACHE_FILE = join(mkdtempSync(join(tmpdir(), 'tmdb-')), 'tmdb.json')
  process.env.TORRENTIO_CACHE_FILE = join(mkdtempSync(join(tmpdir(), 'torrentio-')), 'torrentio.json')
  delete process.env.REAL_DEBRID_TOKEN
  delete process.env.TORBOX_TOKEN
  const urls: string[] = []
  const spy = spyOn(globalThis, 'fetch').mockImplementation((async (url: string) => (urls.push(url), url.includes('themoviedb') ? Response.json({ ...movie, imdb_id: imdb }) : torrentioAnswer())) as typeof fetch)
  return Object.assign(spy, { urls })
}

test('a failing Torrentio leaves the IPTV copies, marked rd unavailable', async () => {
  const spy = tmdbThen('tt1375666', async () => new Response('', { status: 403 }))
  try {
    const c = await titleCopies('movie:27204', Promise.resolve(iptv))
    expect(c).toMatchObject({ originalLanguage: 'en', runtime: 148, sources: { iptv: 'ok', rd: 'unavailable' } })
    expect(c.copies).toEqual(iptv)
  } finally {
    spy.mockRestore()
  }
})

test('a slow Torrentio never holds the IPTV copies back; its answer is there for the next ask', async () => {
  const waiting: ((r: Response) => void)[] = []
  const spy = tmdbThen('tt0000002', () => new Promise((r) => waiting.push(r)))
  try {
    const first = await titleCopies('movie:27206', Promise.resolve(iptv), 50)
    expect(first.sources.rd).toBe('pending')
    expect(first.copies).toEqual(iptv)
    for (const answer of waiting) answer(Response.json(torrentio)) // Torrentio and TorrentsDB
    const next = await titleCopies('movie:27206', Promise.resolve(iptv), 50)
    expect(next.sources.rd).toBe('ok')
    // Bitrate is size over TMDB's runtime (148 min): the 66 GB remux is ~64 Mbps.
    expect(next.copies.find((c) => c.src === 'rd' && c.remux)?.kbps).toBe(Math.round((66.25 * 2 ** 30 * 8) / (148 * 60) / 1000))
  } finally {
    spy.mockRestore()
  }
})

test('with a Real-Debrid key: hash and file come from the resolve URL, the key never leaves, refused hashes drop out', async () => {
  const key = 'SECRETKEY0123456789'
  const spy = tmdbThen('tt0000003', async () => new Response(JSON.stringify(rdList).replaceAll('KEY', key)))
  process.env.REAL_DEBRID_TOKEN = key
  process.env.RD_INFRINGING_FILE = join(mkdtempSync(join(tmpdir(), 'rd-')), 'rd-infringing.json')
  try {
    const c = await titleCopies('movie:27207', Promise.resolve([]))
    expect(spy.urls.some((u) => u.includes(`/realdebrid=${key}/stream/movie/tt0000003.json`))).toBe(true)
    expect(JSON.stringify(c)).not.toContain(key)
    expect(c.copies).toMatchObject([{ hash: '2849211f150e966ef7924f43aec22727a644e10d', file: 0, cached: true }, { hash: 'e31e243b45f18abaa837034d1f7d14d212b4518f' }])
    expect(c.copies[1]).not.toHaveProperty('cached')
    expect(c.copies[1]).not.toHaveProperty('file')
    expect(await markInfringing(['2849211F150E966EF7924F43AEC22727A644E10D'])).toBe(true)
    expect((await titleCopies('movie:27207', Promise.resolve([]))).copies.map((x) => (x as { hash: string }).hash)).toEqual(['e31e243b45f18abaa837034d1f7d14d212b4518f'])
  } finally {
    spy.mockRestore()
  }
})
