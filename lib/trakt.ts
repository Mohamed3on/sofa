// Trakt through the box (KTD19). The stick's Options start Trakt's device-code sign-in here; the box polls
// for the owner's answer and keeps the tokens in .cache/trakt-token.json (atomic writes), refreshing a
// quarter of the token's lifetime before it expires and saving the rotated refresh token. Films and episodes
// the stick finished are written to Trakt history, and so are its marks and ratings. New Trakt apps are public clients: TRAKT_CLIENT_SECRET is
// sent only when set. A refresh Trakt refuses leaves "reconnect" until the owner signs in again.
import { readFile, rm } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { writeAtomic } from './fs.js'
import { parseTitleId } from './tmdb.js'

const API = 'https://api.trakt.tv'
const tokenFile = () => process.env.TRAKT_TOKEN_FILE ?? fileURLToPath(new URL('../.cache/trakt-token.json', import.meta.url))
const clientId = () => process.env.TRAKT_CLIENT_ID ?? ''
const secret = () => (process.env.TRAKT_CLIENT_SECRET ? { client_secret: process.env.TRAKT_CLIENT_SECRET } : {})

/** The kept sign-in: [expires] and [at] in epoch ms, [user] the account's username. */
export type Token = { access: string; refresh: string; expires: number; at: number; user?: string }
export type Status =
  | { state: 'off' | 'expired' | 'denied' }
  | { state: 'waiting'; code: string; url: string }
  | { state: 'on' | 'reconnect'; user: string }

/** Headers for a Trakt API call, signed in when [access] is given. */
export const traktHeaders = (access?: string): Record<string, string> => ({
  'content-type': 'application/json', 'trakt-api-version': '2', 'trakt-api-key': clientId(), 'user-agent': 'iptv-preview',
  ...(access && { authorization: `Bearer ${access}` }),
})
const post = (path: string, body: unknown, access?: string) =>
  fetch(API + path, { method: 'POST', headers: traktHeaders(access), body: JSON.stringify(body), signal: AbortSignal.timeout(10_000) })

/** When [t] is refreshed: a quarter of its lifetime before it expires (6 h for a 24 h token). */
export const refreshAt = (t: Token) => t.expires - (t.expires - t.at) / 4

let token: Promise<Token | null> | null = null
let refused = false // Trakt refused the refresh token: the owner signs in again
let sign: (Status & { until?: number }) | null = null // the device sign-in under way, or how it ended
let poll: ReturnType<typeof setTimeout> | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let refreshing: Promise<Token | null> | null = null
let renews = true

/** For a process besides the server (the nightly build): it reads the tokens and never refreshes them, so the two never race to rotate one. */
export function readOnlyTokens() {
  renews = false
  clearTimeout(timer)
}

const load = () => (token ??= readFile(tokenFile(), 'utf8').then((s) => JSON.parse(s) as Token).catch(() => null).then((t) => (schedule(t), t)))

async function save(t: Token | null) {
  token = Promise.resolve(t)
  const file = tokenFile()
  if (!t) return void (await rm(file, { force: true }))
  // Owner-only: the tokens sign in as the owner's Trakt account.
  await writeAtomic(file, t, 0o600)
  schedule(t)
}

/** Refreshes [t] when due, so the sign-in stays alive while nothing asks; long waits are re-checked. */
function schedule(t: Token | null, ms = t ? refreshAt(t) - Date.now() : 0) {
  clearTimeout(timer)
  if (t && renews) (timer = setTimeout(() => accessToken().catch(() => {}), Math.min(Math.max(ms, 0), 2 ** 31 - 1))).unref()
}

/** A token from Trakt's answer (device or refresh grant). */
const tokenFrom = (r: { access_token: string; refresh_token: string; expires_in: number; created_at?: number }, user?: string): Token => {
  const at = (r.created_at ?? Date.now() / 1000) * 1000
  return { access: r.access_token, refresh: r.refresh_token, at, expires: at + r.expires_in * 1000, ...(user && { user }) }
}

async function refresh(t: Token): Promise<Token | null> {
  const r = await post('/oauth/token', { refresh_token: t.refresh, client_id: clientId(), ...secret(), redirect_uri: 'urn:ietf:wg:oauth:2.0:oob', grant_type: 'refresh_token' }).catch(() => null)
  if (r?.ok) {
    const next = tokenFrom(await r.json(), t.user)
    refused = false
    await save(next)
    return next
  }
  if (r && (r.status === 400 || r.status === 401)) refused = true
  else schedule(t, 10 * 60_000) // Trakt or the line is down: again in 10 minutes
  return null
}

/** A valid access token for Trakt calls (U16's history and watchlist), refreshed when due; null when not signed in. */
export async function accessToken(now = Date.now()): Promise<string | null> {
  let t = await load()
  if (!t || refused) return null
  if (!renews) return now < t.expires ? t.access : null
  if (now >= refreshAt(t)) t = (await (refreshing ??= refresh(t).finally(() => (refreshing = null)))) ?? (now < t.expires ? t : null)
  return t?.access ?? null
}

/** Trakt answered 401 to a signed-in call: the owner signs in again (Options show Reconnect). */
export function revoked() {
  refused = true
}

/** The sign-in as the stick's Options show it. */
export async function status(): Promise<Status> {
  const t = await load()
  if (sign?.state === 'waiting' && Date.now() > sign.until!) sign = { state: 'expired' }
  if (sign) return sign.state === 'waiting' ? { state: 'waiting', code: sign.code, url: sign.url } : sign
  if (!t) return { state: 'off' }
  return { state: refused ? 'reconnect' : 'on', user: t.user ?? '' }
}

/** Starts a device sign-in: a code for the owner to enter at Trakt, polled here until it is entered, denied or expires. */
export async function connect(): Promise<Status> {
  clearTimeout(poll)
  const r = await post('/oauth/device/code', { client_id: clientId() })
  if (!r.ok) throw new Error(`Trakt ${r.status}`)
  const d = (await r.json()) as { device_code: string; user_code: string; verification_url: string; expires_in: number; interval: number }
  const mine = { state: 'waiting' as const, code: d.user_code, url: d.verification_url, until: Date.now() + d.expires_in * 1000 }
  sign = mine
  let every = d.interval * 1000
  const tick = async () => {
    const r = await post('/oauth/device/token', { code: d.device_code, client_id: clientId(), ...secret() }).catch(() => null)
    if (sign !== mine) return // cancelled, or a newer code
    if (r?.status === 200) {
      const t = tokenFrom(await r.json())
      const me = await fetch(`${API}/users/settings`, { headers: traktHeaders(t.access), signal: AbortSignal.timeout(10_000) })
        .then((s) => s.json() as Promise<{ user?: { username?: string } }>).catch(() => null)
      refused = false
      await save({ ...t, user: me?.user?.username ?? '' })
      sign = null
      return
    }
    if (r?.status === 418) sign = { state: 'denied' }
    else if (r && [404, 409, 410].includes(r.status)) sign = { state: 'expired' }
    else if (Date.now() > mine.until) sign = { state: 'expired' }
    else poll = setTimeout(tick, (every += r?.status === 429 ? 1000 : 0))
  }
  poll = setTimeout(tick, every)
  return status()
}

/** Stops a sign-in under way. */
export function cancel() {
  clearTimeout(poll)
  sign = null
}

/** Forgets the sign-in (and asks Trakt to revoke it). */
export async function disconnect() {
  cancel()
  const t = await load()
  if (t) post('/oauth/revoke', { token: t.access, client_id: clientId(), ...secret() }).catch(() => {})
  refused = false
  clearTimeout(timer)
  await save(null)
}

/**
 * Writes one finished film (movie:TMDB) or episode (tv:TMDB:sNeM), watched at [at] (epoch ms), to Trakt history.
 * "ok" when Trakt has it, a duplicate or a title Trakt doesn't know included; "off" when not signed in; "error" to try later.
 */
export async function logPlay(title: string, at: number): Promise<'ok' | 'off' | 'error'> {
  const p = parseTitleId(title)
  if (!p || (p.kind === 'tv' && p.season == null)) return 'ok'
  const watched_at = new Date(at).toISOString()
  const body = p.kind === 'movie'
    ? { movies: [{ ids: { tmdb: +p.tmdb }, watched_at }] }
    : { shows: [{ ids: { tmdb: +p.tmdb }, seasons: [{ number: p.season, episodes: [{ number: p.episode, watched_at }] }] }] }
  const access = await accessToken()
  if (!access) return 'off'
  const r = await post('/sync/history', body, access).catch(() => null)
  if (r?.status === 401) revoked()
  return r?.ok || r?.status === 409 ? 'ok' : r?.status === 401 ? 'off' : 'error'
}

/** Adds a film or show (movie:N, tv:N) to the Trakt watchlist, or takes it off: "ok", "off" when not signed in, "error". */
export async function setWatchlist(title: string, on: boolean): Promise<'ok' | 'off' | 'error'> {
  const p = parseTitleId(title)
  if (!p || p.season != null) return 'error'
  const access = await accessToken()
  if (!access) return 'off'
  const body = p.kind === 'movie' ? { movies: [{ ids: { tmdb: +p.tmdb } }] } : { shows: [{ ids: { tmdb: +p.tmdb } }] }
  const r = await post(on ? '/sync/watchlist' : '/sync/watchlist/remove', body, access).catch(() => null)
  if (r?.status === 401) revoked()
  return r?.ok ? 'ok' : r?.status === 401 ? 'off' : 'error'
}

/** A film, show, season or episode the stick can mark: movie:N, tv:N, tv:N:sS, tv:N:sSeE. */
export const MARKABLE = /^(movie|tv):(\d+)(?::s(\d+)(?:e(\d+))?)?$/

/** Trakt's sync body for [title] (see [MARKABLE]), [extra] (a rating) on its innermost item; [seasons]: a whole show's. */
function syncBody(title: string, extra: object, seasons: number[] = []) {
  const [, kind, id, s, e] = title.match(MARKABLE)!
  const ids = { tmdb: +id }
  if (kind === 'movie') return { movies: [{ ids, ...extra }] }
  if (s == null) return { shows: [{ ids, ...extra, ...(seasons.length && { seasons: seasons.map((number) => ({ number })) }) }] }
  return { shows: [{ ids, seasons: [{ number: +s, ...(e == null ? extra : { episodes: [{ number: +e, ...extra }] }) }] }] }
}

/**
 * Marks [title] watched (now) or not, and rates it 1–10 or clears its rating (null), on Trakt; [seasons] for a
 * whole show, so its specials stay unmarked. "ok", "off" when not signed in, "error".
 */
export async function mark(title: string, { watched, rating }: { watched?: boolean; rating?: number | null }, seasons: number[] = []): Promise<'ok' | 'off' | 'error'> {
  const access = await accessToken()
  if (!access) return 'off'
  const rs = await Promise.all([
    watched != null && post(watched ? '/sync/history' : '/sync/history/remove', syncBody(title, {}, seasons), access),
    rating !== undefined && post(rating ? '/sync/ratings' : '/sync/ratings/remove', syncBody(title, rating ? { rating } : {}), access),
  ].filter((c) => c !== false).map((c) => c.catch(() => null)))
  if (rs.some((r) => r?.status === 401)) revoked()
  return rs.every((r) => r?.ok) ? 'ok' : rs.some((r) => r?.status === 401) ? 'off' : 'error'
}

/** For tests: forget what this process holds. */
export function resetTrakt() {
  cancel()
  clearTimeout(timer)
  token = null
  refused = false
  renews = true
}
