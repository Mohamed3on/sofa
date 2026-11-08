// Production server on the Hetzner box, behind the Cloudflare Tunnel (see README): every /api route in one
// table. A route declares its method, token gate, how long its answer is kept and which kept answers it makes
// stale; its handler returns data (sent as JSON) or a Response of its own (XML, a file, a 4xx). The server owns
// the rest: the gate, a 502 naming any failure, Cache-Control, and the in-memory cache: an answer is reused
// while fresh, then served stale while one rebuild runs, so TiviMate rarely waits on a build, and concurrent
// misses never download the guides twice.
import * as app from './api/app'
import epg from './api/epg'
import playlist from './api/playlist'
import { accessToken } from './lib/trakt'

/** Seconds an answer is reused, then served stale while one rebuild runs; or 'no-store', never kept. */
type Lifetime = { fresh: number; stale?: number } | 'no-store'
export interface Route<T = unknown> {
  /** The one method answered; any when absent. */
  method?: 'POST'
  /** 'playlist': ?t= is PLAYLIST_TOKEN, a public feed for TiviMate. 'app': one of APP_TOKENS, private to the stick. */
  gate: 'playlist' | 'app'
  /** An OK answer's lifetime, or one per answer; 'etag': { body, etag? } answered with ETag/304; absent: no Cache-Control. */
  cache?: Lifetime | ((data: T) => Lifetime) | 'etag'
  /** The kept answers (by URL) an OK answer makes stale. */
  invalidates?: (data: T, kept: URL) => boolean
  run: (req: Request) => Promise<T | Response>
}
/** A route whose callbacks read its handler's answer. */
const typed = <T>(route: Route<T>) => route as unknown as Route

export const routes: Record<string, Route> = {
  '/api/playlist': { gate: 'playlist', cache: { fresh: 1800, stale: 3600 }, run: playlist },
  // No stale serving: a stale guide is exactly the old-names guide.
  '/api/epg': { gate: 'playlist', cache: { fresh: 900 }, run: epg },
  '/api/app/channels': { gate: 'app', cache: { fresh: 1800, stale: 3600 }, run: app.channels },
  '/api/app/guide': { gate: 'app', cache: { fresh: 900 }, run: app.guide },
  '/api/app/matches': { gate: 'app', cache: { fresh: 20 }, run: app.matches },
  '/api/app/movies': { gate: 'app', cache: { fresh: 1800, stale: 3600 }, run: app.movies },
  '/api/app/vod-search': { gate: 'app', cache: { fresh: 600, stale: 3600 }, run: app.vodSearch },
  '/api/app/shows': { gate: 'app', cache: { fresh: 1800, stale: 86400 }, run: app.shows },
  '/api/app/movie': { gate: 'app', cache: { fresh: 604800, stale: 604800 }, run: app.movie },
  '/api/app/episodes': { gate: 'app', cache: { fresh: 21600, stale: 86400 }, run: app.episodes },
  '/api/app/dubs': { gate: 'app', cache: { fresh: 600, stale: 86400 }, run: app.dubs },
  // Kept once both sources answered (Torrentio's cached flags change).
  '/api/app/copies': typed({ gate: 'app', cache: (c) => (c.sources.iptv === 'ok' && c.sources.rd === 'ok' ? { fresh: 3600, stale: 86400 } : 'no-store'), run: app.copies }),
  // Kept once IMDb's histogram is in; until then each ask builds it again, so scores asked now show up.
  '/api/app/title': typed({ gate: 'app', cache: (t) => (t.s !== undefined ? { fresh: 21600, stale: 86400 } : 'no-store'), run: app.title }),
  '/api/app/search': { gate: 'app', cache: { fresh: 1800, stale: 3600 }, run: app.search },
  '/api/app/person': { gate: 'app', cache: { fresh: 86400, stale: 604800 }, run: app.person },
  '/api/app/subtitles': { gate: 'app', cache: 'no-store', run: app.subtitles },
  '/api/app/subtitle': { gate: 'app', cache: 'no-store', run: app.subtitle },
  // A new build must show at once, and the APK is too big to hold in memory.
  '/api/app/version': { gate: 'app', cache: 'no-store', run: app.version },
  '/api/app/apk': { gate: 'app', cache: 'no-store', run: app.apk },
  '/api/app/dm': { gate: 'app', cache: 'no-store', run: app.dm },
  // Answered from their own memory.
  '/api/app/library': { gate: 'app', cache: 'etag', run: app.library },
  '/api/app/history': { gate: 'app', cache: 'etag', run: app.history },
  '/api/app/trakt': { gate: 'app', cache: 'no-store', run: app.traktSignIn },
  // A stored reading can reorder feeds, so the channel lists rebuild on their next request.
  '/api/app/quality': typed({ method: 'POST', gate: 'app', cache: 'no-store', invalidates: (r, u) => r.stored && ['/api/app/channels', '/api/playlist'].includes(u.pathname), run: app.quality }),
  '/api/app/bitrate': { method: 'POST', gate: 'app', cache: 'no-store', run: app.bitrate },
  // A new reading changes that film's info and copies.
  '/api/app/film-quality': typed({ method: 'POST', gate: 'app', cache: 'no-store', invalidates: (r, u) => r.stored && ((u.pathname === '/api/app/movie' && u.searchParams.get('id') === String(r.id)) || u.pathname === '/api/app/copies'), run: app.filmQuality }),
  '/api/app/events': { method: 'POST', gate: 'app', run: app.events },
  '/api/app/trakt-history': { method: 'POST', gate: 'app', run: app.traktHistory },
  '/api/app/watchlist': { method: 'POST', gate: 'app', run: app.watchlist },
  '/api/app/mark': { method: 'POST', gate: 'app', run: app.mark },
  // The films the stick asked to log on Letterboxd, for the TrueScore extension in the owner's Chrome (lib/letterboxd-log.ts).
  '/api/letterboxd/logs': { gate: 'app', cache: 'no-store', run: app.letterboxdLogs },
  '/api/app/title-ids': { method: 'POST', gate: 'app', cache: 'no-store', run: app.titleIds },
  // A refused torrent leaves every title's copies at once.
  '/api/app/rd-infringing': typed({ method: 'POST', gate: 'app', cache: 'no-store', invalidates: (r, u) => r.stored && u.pathname === '/api/app/copies', run: app.rdInfringing }),
}

type Entry = { body: Blob; init: ResponseInit; fresh: number; stale: number }
const cache = new Map<string, Entry>()
const building = new Map<string, Promise<Entry>>()
// Bumped by each invalidation, so a build already running for an invalidated URL doesn't keep its stale answer.
let epoch = 0

/** ?t= is PLAYLIST_TOKEN, or one of the comma-separated APP_TOKENS (two at once while rotating). */
function gateOpen(gate: Route['gate'], url: URL): boolean {
  const t = url.searchParams.get('t')
  const tokens = gate === 'playlist' ? [process.env.PLAYLIST_TOKEN] : (process.env.APP_TOKENS ?? '').split(',').map((s) => s.trim())
  return !!t && tokens.includes(t)
}

/** [body] as JSON with its ETag, or an empty 304 when If-None-Match names it (Cloudflare weakens it, W/, when it compresses). */
function etagged(req: Request, { body, etag = `"${Bun.hash(body).toString(36)}"` }: { body: string; etag?: string }): Response {
  const headers = { ETag: etag, 'Cache-Control': 'private, no-cache' }
  if (req.headers.get('if-none-match')?.replace(/^W\//, '') === etag) return new Response(null, { status: 304, headers })
  return new Response(body, { headers: { ...headers, 'Content-Type': 'application/json' } })
}

/** The route's response, and its lifetime when it is to be kept. */
async function answer(req: Request, route: Route): Promise<[Response, { fresh: number; stale?: number }?]> {
  let data: unknown
  try {
    data = await route.run(req)
  } catch (e) {
    return [new Response(e instanceof Error ? e.message : 'upstream error', { status: 502 })]
  }
  if (route.cache === 'etag') return [etagged(req, data as { body: string; etag?: string })]
  const res = data instanceof Response ? data : Response.json(data)
  if (!res.ok) return [res]
  if (route.invalidates) {
    for (const url of new Set([...cache.keys(), ...building.keys()])) {
      if (!route.invalidates(data, new URL(url))) continue
      cache.delete(url)
      epoch++
    }
  }
  const life = typeof route.cache === 'function' ? route.cache(data) : route.cache
  if (!life) return [res]
  const scope = route.gate === 'playlist' ? 'public' : 'private'
  res.headers.set('Cache-Control', life === 'no-store' ? life : `${scope}, s-maxage=${life.fresh}${life.stale ? `, stale-while-revalidate=${life.stale}` : ''}`)
  return [res, life === 'no-store' ? undefined : life]
}

// One build per URL at a time; an answer with a lifetime is kept for the requests after it.
function build(url: string, req: Request, route: Route): Promise<Entry> {
  const running = building.get(url)
  if (running) return running
  const began = epoch
  const job = (async () => {
    const [res, life] = await answer(req, route)
    const now = Date.now()
    const fresh = now + (life?.fresh ?? 0) * 1000
    const entry = { body: await res.blob(), init: { status: res.status, headers: res.headers }, fresh, stale: fresh + (life?.stale ?? 0) * 1000 }
    for (const [k, e] of cache) if (e.stale <= now) cache.delete(k)
    if (life && began === epoch) cache.set(url, entry)
    return entry
  })().finally(() => building.delete(url))
  building.set(url, job)
  return job
}

async function cached(req: Request, route: Route): Promise<Response> {
  const hit = cache.get(req.url)
  const now = Date.now()
  if (hit && now >= hit.fresh && now < hit.stale) build(req.url, req, route).catch(() => {})
  const entry = hit && now < hit.stale ? hit : await build(req.url, req, route)
  return new Response(entry.body, entry.init)
}

export async function handle(req: Request): Promise<Response> {
  const url = new URL(req.url)
  const route = routes[url.pathname]
  if (!route || (route.method && req.method !== route.method)) return new Response('Not found', { status: 404 })
  if (!gateOpen(route.gate, url)) return new Response('Forbidden', { status: 403 })
  // Only routes that may keep an answer go through the cache: the rest never hold a body in memory.
  return typeof route.cache === 'object' || typeof route.cache === 'function' ? cached(req, route) : (await answer(req, route))[0]
}

if (import.meta.main) {
  // Keeps a Trakt sign-in alive across restarts: reading the token schedules its refresh.
  accessToken().catch(() => {})
  Bun.serve({
    hostname: '127.0.0.1',
    port: Number(process.env.PORT ?? 8790),
    // A cold EPG build takes ~15s, and Bun drops a connection idle for 10s by default.
    idleTimeout: 255,
    fetch: handle,
  })
}
