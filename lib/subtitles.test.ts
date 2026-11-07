import { afterEach, expect, spyOn, test } from 'bun:test'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { subtitlesFor } from './subtitles'

const env = { ...process.env }
afterEach(() => {
  process.env = { ...env }
})

const addon = {
  subtitles: [
    { url: 'https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/1', lang: 'eng', m: 'i', movieReleaseName: 'Inception.2010.CAM.Xvid-LKRG' },
    { url: 'https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/2', lang: 'fre', m: 'h', movieReleaseName: 'Inception.2010.1080p.BluRay.x264-SPARKS' },
    { url: 'https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/3', lang: 'ara', m: 'h', movieReleaseName: 'Inception.2010.1080p.BluRay.x264-SPARKS' },
    { url: 'https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/4', lang: 'eng', m: 'i', movieReleaseName: 'Inception.2010.1080p.BluRay.x264-SPARKS' },
    { url: 'https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/5', lang: 'eng', m: 'h', movieReleaseName: 'Inception.2010.720p' },
  ],
}

test('a hash asked before is answered from the box, without the addon; English first, a hash match first, then the nearest release', async () => {
  process.env.SUBTITLES_DIR = mkdtempSync(join(tmpdir(), 'subs-'))
  const fetch = spyOn(globalThis, 'fetch').mockImplementation((async () => Response.json(addon)) as unknown as typeof globalThis.fetch)
  try {
    const first = await subtitlesFor('tt1375666', '8e245d9679d31e12', 1_000_000, 'Inception 2010 1080p BluRay x264-SPARKS')
    expect(String(fetch.mock.calls[0][0])).toBe('https://opensubtitles-v3.strem.io/subtitles/movie/tt1375666/videoHash=8e245d9679d31e12&videoSize=1000000.json')
    expect(first.status).toBe('ok')
    expect(first.subs.map((s) => s.id)).toEqual(['5', '4', '1', '3'])
    const again = await subtitlesFor('tt1375666', '8e245d9679d31e12', 1_000_000, 'Inception 2010 1080p BluRay x264-SPARKS')
    expect(again).toEqual(first)
    expect(fetch).toHaveBeenCalledTimes(1)
  } finally {
    fetch.mockRestore()
  }
})

test('a source refusing for today answers "quota" and is asked again next time', async () => {
  process.env.SUBTITLES_DIR = mkdtempSync(join(tmpdir(), 'subs-'))
  const fetch = spyOn(globalThis, 'fetch').mockImplementation((async () => new Response('', { status: 429 })) as unknown as typeof globalThis.fetch)
  try {
    expect(await subtitlesFor('tt1375666', '', 0, '')).toEqual({ status: 'quota', subs: [] })
    const calls = fetch.mock.calls.length
    await subtitlesFor('tt1375666', '', 0, '')
    expect(fetch.mock.calls.length).toBeGreaterThan(calls)
  } finally {
    fetch.mockRestore()
  }
})
