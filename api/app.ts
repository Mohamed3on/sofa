// The Fire TV app's endpoints, gated by APP_TOKENS (never PLAYLIST_TOKEN) and private:
// no CORS header, and only the server's own cache may keep a response.
// GET /api/app/channels?t=TOKEN — groups, channels, feed families, badges and both provider fronts.
// GET /api/app/guide?t=TOKEN — the guide as a gzip SQLite file the stick opens without parsing.
// GET /api/app/movies?t=TOKEN — the on-demand library: 4K movies, UFC replays and Blu-ray films.
// GET /api/app/vod-search?t=TOKEN&q=… — films across the provider's whole catalogue.
// GET /api/app/version, /api/app/apk, /api/app/dm ?t=TOKEN — the latest build and its dex metadata (the
// baseline profile, compiled at install), copied to the box by firetv.yml.
// POST /api/app/quality?t=TOKEN — a quality reading the app measured while playing.
// POST /api/app/bitrate?t=TOKEN — a feed's bitrate the app measured while playing.
// POST /api/app/events?t=TOKEN — what happened while watching, for later analysis.
// POST /api/app/title-ids?t=TOKEN — title ids for the provider ids the app saved marks under.
// GET /api/app/library?t=TOKEN — Movies & Shows: titles and rows (see _library.ts).
// GET /api/app/history?t=TOKEN — Trakt and Letterboxd marks, Watchlist, followed shows' next episodes.
// GET /api/app/copies?t=TOKEN&title=… — every copy of a film or episode, for the stick to rank.
// POST /api/app/rd-infringing?t=TOKEN — torrents Real-Debrid refused, left out of copies from then on.
// GET /api/app/subtitles?t=TOKEN&title=…&hash=…&size=…&name=… — English and Arabic subtitles for the copy playing.
// GET /api/app/subtitle?t=TOKEN&id=… — one of them as SRT.
// GET /api/app/title, /api/app/search, /api/app/person ?t=TOKEN — title pages, search and person pages (see _browse.ts; cards from _titles.ts).
// GET /api/app/trakt?t=TOKEN — the Trakt sign-in; POST it { action: connect | cancel | disconnect } to change it.
// POST /api/app/trakt-history?t=TOKEN — { title, at } a film or episode finished on the stick, for Trakt history.
// POST /api/app/mark?t=TOKEN — { title, watched?, rating? } a mark or rating made on the stick, for Trakt.
import { appendFile, mkdir, rename, stat } from 'node:fs/promises'
import { dirname } from 'node:path'
import { buildAppFeed, buildAppGuide } from './_app-feed.js'
import { iptvCopies, markInfringing, titleCopies } from './_copies.js'
import { appTokenOk, fetchCuratedChannels, saneFps, vodChannels, withBackupFeeds, withCompetitionGroups } from './_lib.js'
import { backupConfigFromEnv, configFromEnv } from './_provider.js'
import { currentHistory, historyAnswer, loadHistory, saveHistory, watchedOf, watchlistOf, withMark } from './_history.js'
import { knownTitles, providerLibrary, ranked, rescoredLibrary, servedLibrary } from './_library.js'
import { recordBitrate, recordReading, type Meta } from './_quality.js'
import { curatedGuide } from './epg.js'
import { movieInfo, saveMeasuredFilm, showEpisodes, showList, titleIds as mapTitleIds } from './_shows.js'
import { subtitleFile, subtitlesFor } from './_subtitles.js'
import { personTitles, searchTitles, titleDetails } from './_browse.js'
import { loadScores, scoreTitles, storeAt, watchStore } from './_scores.js'
import { episodeTitle, parseTitleId, tmdbEpisodes, tmdbFacts, tmdbGet, tmdbTitle } from './_tmdb.js'
import * as trakt from './_trakt.js'
import { blurayMovies, filmTitleOf, searchVod } from './_vod.js'

const forbidden = () => new Response('Forbidden', { status: 403 })

/** [body] as JSON with its ETag, or an empty 304 when If-None-Match names it (Cloudflare weakens it, W/, when it compresses). */
function etagged(req: Request, body: string, etag = `"${Bun.hash(body).toString(36)}"`): Response {
  const headers = { ETag: etag, 'Cache-Control': 'private, no-cache' }
  if (req.headers.get('if-none-match')?.replace(/^W\//, '') === etag) return new Response(null, { status: 304, headers })
  return new Response(body, { headers: { ...headers, 'Content-Type': 'application/json' } })
}

export async function channels(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  try {
    const cfg = configFromEnv()
    const backup = backupConfigFromEnv()
    const feed = buildAppFeed(cfg, withCompetitionGroups(await withBackupFeeds(await fetchCuratedChannels(cfg, false), backup)), backup, process.env.REAL_DEBRID_TOKEN, process.env.TORBOX_TOKEN)
    return Response.json(feed, {
      headers: { 'Cache-Control': 'private, s-maxage=1800, stale-while-revalidate=3600' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

export async function guide(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  try {
    const { channels, xml } = await curatedGuide()
    return new Response(buildAppGuide(channels, xml), {
      headers: { 'Content-Type': 'application/gzip', 'Cache-Control': 'private, s-maxage=900' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// Builds live in app/, which deploys never touch, at fixed paths never taken from the request.
// No file extension in the route, so Cloudflare never caches the APK.
const appFile = (name: string) => Bun.file(`${process.env.APP_DIR ?? `${import.meta.dir}/../app`}/${name}`)

async function serveFile(req: Request, name: string, type: string): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const file = appFile(name)
  if (!(await file.exists())) return new Response('Not found', { status: 404 })
  return new Response(file, { headers: { 'Content-Type': type, 'Cache-Control': 'no-store' } })
}

export const version = (req: Request) => serveFile(req, 'version.json', 'application/json')
export const apk = (req: Request) => serveFile(req, 'app.apk', 'application/vnd.android.package-archive')
export const dm = (req: Request) => serveFile(req, 'app.dm', 'application/zip')

// POST /api/app/quality?t=TOKEN — what the app measured on a stream it played. Saved into
// the quality cache under the probe's rules, so normal viewing keeps the ordering current.
const CODEC = /^[a-z0-9.-]{1,16}$/
export async function quality(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const r = (await req.json().catch(() => null)) as Record<string, unknown> | null
  const int = (v: unknown, max: number) => Number.isInteger(v) && (v as number) > 0 && (v as number) <= max
  // Ids reach 2e9: provider-B feeds are BACKUP_ID (1e9) + their own id.
  if (!r || !int(r.id, 2e9) || !int(r.w, 8192) || !int(r.h, 8192) || typeof r.fps !== 'number' ||
      typeof r.codec !== 'string' || !CODEC.test(r.codec) || typeof r.name !== 'string' || r.name.length > 200) {
    return new Response('Bad reading', { status: 400 })
  }
  const reading: Meta = {
    w: r.w as number, h: r.h as number, fps: saneFps(r.fps), codec: r.codec, name: r.name,
    ...(r.atmos === true && { atmos: true as const }),
    ...(r.surround === true && { surround: true as const }),
  }
  const stored = await recordReading(r.id as number, reading, new Date().toISOString().slice(0, 10))
  return Response.json({ stored }, { headers: { 'Cache-Control': 'no-store' } })
}

// POST /api/app/film-quality?t=TOKEN — what a film really is, measured by the app's player on its first
// play: from then on its labels and tier are measured, not the category's claim.
const HDR = new Set(['', 'HDR10', 'HLG', 'Dolby Vision'])
export async function filmQuality(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const r = (await req.json().catch(() => null)) as Record<string, unknown> | null
  const int = (v: unknown, max: number) => Number.isInteger(v) && (v as number) > 0 && (v as number) <= max
  const word = (v: unknown) => typeof v === 'string' && /^[a-z0-9._-]{1,24}$/i.test(v)
  if (!r || !int(r.id, 2e9) || !int(r.w, 8192) || !int(r.h, 8192) || !word(r.codec) || !HDR.has(String(r.hdr ?? '')) ||
      (r.audio !== undefined && !word(r.audio)) || (r.channels !== undefined && !int(r.channels, 16)) ||
      (r.fps !== undefined && !(typeof r.fps === 'number' && r.fps > 0 && r.fps <= 240)) || (r.kbps !== undefined && !int(r.kbps, 500_000))) {
    return new Response('Bad reading', { status: 400 })
  }
  const stored = await saveMeasuredFilm(r.id as number, {
    w: r.w as number, h: r.h as number, codec: r.codec as string,
    ...(typeof r.fps === 'number' && { fps: Math.round(r.fps * 1000) / 1000 }),
    ...(r.hdr ? { hdr: r.hdr as string } : {}),
    ...(r.audio ? { audio: r.audio as string } : {}),
    ...(r.channels ? { channels: r.channels as number } : {}),
    ...(r.atmos === true && { atmos: true }),
    ...(r.kbps ? { kbps: r.kbps as number } : {}),
  })
  return Response.json({ stored, id: r.id }, { headers: { 'Cache-Control': 'no-store' } })
}

// POST /api/app/bitrate?t=TOKEN — a feed's bitrate (Mbps) measured while the app played it,
// kept in the quality cache with the feed's recent ones for the lighter-feed downshift (#4).
export async function bitrate(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const r = (await req.json().catch(() => null)) as Record<string, unknown> | null
  const id = r?.id
  if (!r || !Number.isInteger(id) || !((id as number) > 0 && (id as number) <= 2e9) || typeof r.mbps !== 'number' || !(r.mbps >= 0.1 && r.mbps <= 200)) {
    return new Response('Bad reading', { status: 400 })
  }
  const stored = await recordBitrate(id as number, r.mbps)
  return Response.json({ stored }, { headers: { 'Cache-Control': 'no-store' } })
}

// POST /api/app/events?t=TOKEN — a batch of what happened while watching (tunes and their time to
// picture, stalls, rebuffers, feed switches, busy/wrong-route/offline; a film's copy failovers, step-downs, offers and trials). Appended to a capped log on
// the box for later analysis: which feeds actually hold up, and real zap times.
const EVENT_TYPES = new Set(['tune', 'stall', 'rebuffer', 'failover', 'problem', 'stepdown', 'offer', 'trial'])
const EVENTS_MAX_BYTES = 20 * 1024 * 1024
export async function events(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const batch = await req.json().catch(() => null)
  const ok = Array.isArray(batch) && batch.length <= 200 && batch.every((e) =>
    e && typeof e === 'object' && EVENT_TYPES.has(e.type) && JSON.stringify(e).length <= 500)
  if (!ok) return new Response('Bad events', { status: 400 })
  const file = process.env.APP_EVENTS_FILE ?? `${import.meta.dir}/../.cache/app-events.jsonl`
  const at = new Date().toISOString()
  await mkdir(dirname(file), { recursive: true })
  // Keep one previous file; the log never grows past twice the cap.
  if (((await stat(file).catch(() => null))?.size ?? 0) > EVENTS_MAX_BYTES) await rename(file, `${file}.1`)
  await appendFile(file, batch.map((e: object) => JSON.stringify({ at, ...e })).join('\n') + '\n')
  return new Response(null, { status: 204 })
}

// GET /api/app/movies?t=TOKEN — the on-demand library (4K movies, UFC replays, then Blu-ray films) in playlist order,
// from the saved VOD catalogue (refreshed daily), so the app never adds a pull of the 74MB catalogue.
export async function movies(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  try {
    const cfg = configFromEnv()
    const [vod, titleOf] = await Promise.all([vodChannels(cfg).catch(() => []), filmTitleOf(cfg).catch(() => null)])
    // The 1080p Blu-ray row follows the 4K groups: films with no 4K copy.
    const movies = [...vod.map((m) => ({ id: m.streamId, titleId: titleOf?.(m.streamId) ?? `iptv:${m.streamId}`, name: m.name, poster: m.logo, group: m.group, ext: m.vodExt ?? 'mp4' })), ...(await blurayMovies(cfg).catch(() => []))]
    return Response.json(
      {
        groups: [...new Set(movies.map((m) => m.group))],
        movies,
      },
      { headers: { 'Cache-Control': 'private, s-maxage=1800, stale-while-revalidate=3600' } },
    )
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// GET /api/app/vod-search?t=TOKEN&q=QUERY — films across the provider's whole catalogue (not only the
// library's 4K and Blu-ray rows), one per film on its best copy, in /api/app/movies' item shape.
export async function vodSearch(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const q = (new URL(req.url).searchParams.get('q') ?? '').trim()
  if (!q) return Response.json({ movies: [] })
  try {
    return Response.json({ movies: await searchVod(configFromEnv(), q) }, {
      headers: { 'Cache-Control': 'private, s-maxage=600, stale-while-revalidate=3600' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// GET /api/app/shows?t=TOKEN — the shows (series) in the user's languages, in the movies' shape
// (see _shows.ts); the provider's list is pulled at most once a day.
export async function shows(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  try {
    return Response.json(await showList(configFromEnv()), {
      headers: { 'Cache-Control': 'private, s-maxage=1800, stale-while-revalidate=86400' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// GET /api/app/movie?t=TOKEN&id=VOD_ID — a film's plot, running time, genres and rating, fetched
// when the app shows it.
export async function movie(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const id = Number(new URL(req.url).searchParams.get('id'))
  if (!Number.isInteger(id) || id <= 0) return new Response('Bad id', { status: 400 })
  try {
    const info = await movieInfo(configFromEnv(), id)
    return Response.json({ ...info, ...(await tmdbFacts(info.titleId)) }, {
      headers: { 'Cache-Control': 'private, s-maxage=604800, stale-while-revalidate=604800' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// GET /api/app/episodes?t=TOKEN&id=SERIES_ID — a show's plot and episodes, in order.
export async function episodes(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const id = Number(new URL(req.url).searchParams.get('id'))
  if (!Number.isInteger(id) || id <= 0) return new Response('Bad id', { status: 400 })
  try {
    const show = await showEpisodes(configFromEnv(), id)
    return Response.json({ ...show, ...(await tmdbFacts(show.titleId)) }, {
      headers: { 'Cache-Control': 'private, s-maxage=21600, stale-while-revalidate=86400' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// The provider's rows are cut and sorted like the built library's, so the fallback stays small.
const libraryNow = () => servedLibrary(async () => ranked(await providerLibrary(configFromEnv()), scoreTitles(await loadScores())), async () => rescoredLibrary(watchedOf(await loadHistory())), storeAt)
// A score TrueScore's extension adds reaches the library's rows a second or so after it lands in the store.
watchStore(() => void libraryNow().catch(() => {}))

// GET /api/app/library?t=TOKEN — the Movies & Shows library the nightly job builds (see _library.ts), redone from
// TrueScore's store and the scores whenever the store changes, held in memory; a request whose If-None-Match names
// the current ETag gets an empty 304.
export async function library(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  try {
    const { body, etag } = await libraryNow()
    return etagged(req, body, etag)
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// GET /api/app/history?t=TOKEN&shows=tv:N:sSeE@ms,… — the owner's history (see _history.ts): marks from Trakt and
// Letterboxd, the Watchlist row, and the next episode of each followed show ([shows]: the stick's
// own, the episode each last finished and when). Read again when over 10 minutes old; ETag/304 like the library.
export async function history(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  try {
    const local = new URL(req.url).searchParams.get('shows')?.split(',').filter(Boolean) ?? []
    return etagged(req, JSON.stringify(await historyAnswer(await currentHistory(), local, await knownTitles())))
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// POST /api/app/title-ids?t=TOKEN — { movies?: [vodId], shows?: [seriesId], episodes?: [{ series, id }] } to
// { movies: { vodId: titleId }, shows: { seriesId: titleId }, episodes: { episodeId: titleId } }: the one-time
// move of the app's resume and watched marks from provider ids to title ids (see _shows.ts titleIds).
export async function titleIds(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const r = (await req.json().catch(() => null)) as { movies?: unknown; shows?: unknown; episodes?: unknown } | null
  const id = (v: unknown) => Number.isInteger(v) && (v as number) > 0 && (v as number) <= 2e9
  const list = (v: unknown, ok: (x: any) => boolean) => v === undefined || (Array.isArray(v) && v.length <= 5000 && v.every(ok))
  const eps = r?.episodes as { series: number; id: number }[] | undefined
  if (!r || !list(r.movies, id) || !list(r.shows, id) || !list(eps, (e) => e && id(e.series) && id(e.id)) || new Set(eps?.map((e) => e.series)).size > 100) {
    return new Response('Bad ids', { status: 400 })
  }
  try {
    return Response.json(await mapTitleIds(configFromEnv(), { movies: r.movies as number[], shows: r.shows as number[], episodes: eps }), {
      headers: { 'Cache-Control': 'no-store' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// GET /api/app/copies?t=TOKEN&title=TITLE_ID — every copy of a film or episode, IPTV copies and Real-Debrid
// candidates, with what each is (see _copies.ts); the stick ranks them. Kept an hour once both sources
// answered (Torrentio's cached flags change).
const COPY_TITLE = /^(?:movie:\d+|iptv:\d+|(?:tv|iptv:tv):\d+:s\d+e\d+)$/
export async function copies(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const title = new URL(req.url).searchParams.get('title') ?? ''
  if (!COPY_TITLE.test(title)) return new Response('Bad title', { status: 400 })
  try {
    const c = await titleCopies(title, iptvCopies(configFromEnv(), title))
    const settled = c.sources.iptv === 'ok' && c.sources.rd === 'ok'
    return Response.json(c, { headers: { 'Cache-Control': settled ? 'private, s-maxage=3600, stale-while-revalidate=86400' : 'no-store' } })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// POST /api/app/rd-infringing?t=TOKEN — { hashes: [infoHash] } Real-Debrid refused here (451): no title
// offers them again for 60 days.
export async function rdInfringing(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const hashes = ((await req.json().catch(() => null)) as { hashes?: unknown } | null)?.hashes
  if (!Array.isArray(hashes) || !hashes.length || hashes.length > 200 || !hashes.every((h) => typeof h === 'string' && /^[0-9a-f]{40}$/i.test(h))) {
    return new Response('Bad hashes', { status: 400 })
  }
  return Response.json({ stored: await markInfringing(hashes) }, { headers: { 'Cache-Control': 'no-store' } })
}

// GET /api/app/subtitles?t=TOKEN&title=TITLE_ID&hash=OSHASH&size=BYTES&name=RELEASE — English, then Arabic, subtitles for a
// film or episode, best first for the copy playing (its OpenSubtitles hash and release name; see _subtitles.ts).
export async function subtitles(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const q = new URL(req.url).searchParams
  const title = q.get('title') ?? ''
  const hash = q.get('hash') ?? ''
  const size = Number(q.get('size') ?? 0)
  if (!COPY_TITLE.test(title)) return new Response('Bad title', { status: 400 })
  if (!/^(?:[0-9a-f]{16})?$/.test(hash) || !Number.isSafeInteger(size) || size < 0) return new Response('Bad hash or size', { status: 400 })
  const { imdb } = await tmdbFacts(title)
  const ep = parseTitleId(title)
  const answer = imdb ? await subtitlesFor(ep?.season != null ? `${imdb}:${ep.season}:${ep.episode}` : imdb, hash, size, (q.get('name') ?? '').slice(0, 300)) : { status: 'none', subs: [] }
  return Response.json(answer, { headers: { 'Cache-Control': 'no-store' } })
}

// GET /api/app/subtitle?t=TOKEN&id=ID — a subtitle file from /api/app/subtitles, as UTF-8 SRT; 429 when today's downloads are spent.
export async function subtitle(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const id = new URL(req.url).searchParams.get('id') ?? ''
  if (!/^(?:os)?\d{1,12}$/.test(id)) return new Response('Bad id', { status: 400 })
  try {
    const srt = await subtitleFile(id)
    if (srt === 'quota') return new Response('No subtitles today', { status: 429 })
    return new Response(srt, { headers: { 'Content-Type': 'application/x-subrip; charset=utf-8', 'Cache-Control': 'no-store' } })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// GET /api/app/title?t=TOKEN&id=movie:27205|tv:1396 — a title page's TMDB details: plot, backdrop, cast and
// director, More like this, its score, and a show's episodes when the provider lacks it (see _browse.ts).
export async function title(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const id = new URL(req.url).searchParams.get('id') ?? ''
  if (!/^(movie|tv):\d+$/.test(id)) return new Response('Bad id', { status: 400 })
  try {
    const t = await titleDetails(configFromEnv(), id)
    if (!t) return new Response('Unknown title', { status: 404 })
    // Kept 6 hours once IMDb's histogram is in; until then each ask builds it again, so scores asked now show up.
    return Response.json(t, { headers: { 'Cache-Control': t.s !== undefined ? 'private, s-maxage=21600, stale-while-revalidate=86400' : 'private, no-store' } })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// GET /api/app/search?t=TOKEN&q=… — films and shows for a search: a category word's row or TMDB discover,
// else TMDB's title search plus the provider's catalogue (see _browse.ts searchTitles).
export async function search(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const q = (new URL(req.url).searchParams.get('q') ?? '').trim().slice(0, 100)
  if (!q) return Response.json({ titles: [] })
  const cfg = configFromEnv()
  const h = await currentHistory().catch(() => null)
  const titles = await searchTitles(q, {
    history: h ? { watchlist: new Set(watchlistOf(h)), watched: new Set([...h.movies, ...(h.lb ?? []), ...Object.keys(h.imdb ?? {})]) } : undefined,
    known: await knownTitles(),
    tmdb: process.env.TMDB_TOKEN ? (path) => tmdbGet(path) : undefined,
    vod: (q) => searchVod(cfg, q, 20),
  })
  return Response.json({ titles }, { headers: { 'Cache-Control': 'private, s-maxage=1800, stale-while-revalidate=3600' } })
}

// GET /api/app/person?t=TOKEN&id=TMDB_PERSON_ID — a person's films and shows, best score first.
export async function person(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const id = new URL(req.url).searchParams.get('id') ?? ''
  if (!/^[1-9]\d{0,9}$/.test(id)) return new Response('Bad id', { status: 400 })
  if (!process.env.TMDB_TOKEN) return new Response('No TMDB', { status: 404 })
  try {
    const p = await tmdbGet<{ name?: string; combined_credits?: Parameters<typeof personTitles>[0] }>(`person/${id}?append_to_response=combined_credits`)
    return Response.json({ name: p.name ?? '', titles: personTitles(p.combined_credits ?? {}, await knownTitles()) }, {
      headers: { 'Cache-Control': 'private, s-maxage=86400, stale-while-revalidate=604800' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// GET /api/app/trakt?t=TOKEN — the Trakt sign-in as Options show it (see _trakt.ts); POST { action } connects
// (a new device code), cancels a code or disconnects, and answers the new state.
export async function traktSignIn(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const headers = { 'Cache-Control': 'no-store' }
  if (req.method !== 'POST') return Response.json(await trakt.status(), { headers })
  const action = ((await req.json().catch(() => null)) as { action?: unknown } | null)?.action
  try {
    if (action === 'connect') return Response.json(await trakt.connect(), { headers })
    if (action === 'cancel') trakt.cancel()
    else if (action === 'disconnect') await trakt.disconnect()
    else return new Response('Bad action', { status: 400 })
    return Response.json(await trakt.status(), { headers })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })
  }
}

// POST /api/app/watchlist?t=TOKEN — { title: movie:N | tv:N, on: boolean } adds it to the Trakt watchlist or takes it
// off, and to the kept history at once so the Watchlist row follows: 204, 503 while Trakt isn't signed in, 502 when it fails.
export async function watchlist(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const r = (await req.json().catch(() => null)) as { title?: unknown; on?: unknown } | null
  if (!r || typeof r.title !== 'string' || !/^(?:movie|tv):\d+$/.test(r.title) || typeof r.on !== 'boolean') return new Response('Bad title', { status: 400 })
  const title = r.title
  const result = await trakt.setWatchlist(title, r.on)
  if (result !== 'ok') return new Response(result === 'off' ? 'Trakt not connected' : 'Trakt failed', { status: result === 'off' ? 503 : 502 })
  const h = await loadHistory()
  // Its runtime too, so the row (shortest first) places it at once.
  const runtime = r.on ? (await tmdbTitle(title.startsWith('tv:') ? 'tv' : 'movie', title.split(':')[1]))?.runtime : 0
  if (h) await saveHistory({ ...h, watchlist: [...(r.on ? [title] : []), ...h.watchlist.filter((t) => t !== title)], ...(runtime && { runtimes: { ...h.runtimes, [title]: runtime } }) })
  return new Response(null, { status: 204 })
}

// POST /api/app/mark?t=TOKEN — { title: movie:N | tv:N | tv:N:sS | tv:N:sSeE, watched?: boolean, rating?: 1–10 | null }:
// marks it watched (a season or show: its aired episodes) or not, rates it or clears its rating, on Trakt and in the
// kept history at once, so the stick's next history has it: 204, 503 while Trakt isn't signed in, 502 when it fails.
export async function mark(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const r = (await req.json().catch(() => null)) as { title?: unknown; watched?: unknown; rating?: unknown } | null
  const m = typeof r?.title === 'string' ? r.title.match(trakt.MARKABLE) : null
  const { watched, rating } = r ?? {}
  if (!m || (watched !== undefined && typeof watched !== 'boolean') || (rating !== undefined && rating !== null && !(Number.isInteger(rating) && (rating as number) >= 1 && (rating as number) <= 10))
    || (watched === undefined && rating === undefined) || (m[3] && !m[4] && rating !== undefined) || (!m[3] && m[1] === 'tv' && watched === false)) {
    return new Response('Bad mark', { status: 400 })
  }
  const title = m[0]
  // A season or show covers its aired episodes (specials only when the season is asked for).
  const show = m[1] === 'tv' && !m[4] && watched !== undefined ? await tmdbTitle('tv', m[2]) : null
  const covered = show ? (await tmdbEpisodes(m[2], show.seasons)).filter((e) => (m[3] ? e.season === +m[3] : e.season > 0)).map((e) => episodeTitle(`tv:${m[2]}`, e.season, e.episode)) : [title]
  const result = await trakt.mark(title, { watched: watched as boolean | undefined, rating: rating as number | null | undefined }, m[3] ? [] : show?.seasons)
  if (result !== 'ok') return new Response(result === 'off' ? 'Trakt not connected' : 'Trakt failed', { status: result === 'off' ? 503 : 502 })
  const h = await loadHistory()
  if (h) await saveHistory(withMark(h, title, covered, watched as boolean | undefined, rating as number | null | undefined))
  return new Response(null, { status: 204 })
}

// POST /api/app/trakt-history?t=TOKEN — { title: movie:N | tv:N:sNeM, at: epoch ms } a play the stick finished: 204 once
// Trakt has it (a duplicate too), 503 while Trakt isn't signed in and 502 when it fails, so the stick keeps it for later.
export async function traktHistory(req: Request): Promise<Response> {
  if (!appTokenOk(req.url)) return forbidden()
  const r = (await req.json().catch(() => null)) as { title?: unknown; at?: unknown } | null
  if (!r || typeof r.title !== 'string' || !COPY_TITLE.test(r.title) || !Number.isSafeInteger(r.at) || (r.at as number) < 1.5e12 || (r.at as number) > Date.now() + 864e5) {
    return new Response('Bad play', { status: 400 })
  }
  const result = await trakt.logPlay(r.title, r.at as number)
  if (result === 'ok') return new Response(null, { status: 204 })
  return new Response(result === 'off' ? 'Trakt not connected' : 'Trakt failed', { status: result === 'off' ? 503 : 502 })
}
