// Shared logic for the trimmed StrongTV playlist + EPG endpoints.
// Curation: KEEP ids + auto-include rules -> mapped into viewer-friendly
// BUCKETS; channels quality-sorted (FHD+ first), language as tie-break
// (EN > AR > DE/ES), dead PPV slots dropped, catch-up flags passed through.
// UFC PPV replays + the provider's 4K/UHD movie libraries (all languages) are
// appended as on-demand VOD entries. Live kids channels (EN/US/AR/ES/DE, plus
// Russian kids pulled by name from the general RU category) share one Kids group.
// Per-competition "🏆" groups (withCompetitionGroups) copy football channels from
// every region under the league/cup they carry, quality first.
// Credentials come from env (XTREAM_HOST, XTREAM_USER, XTREAM_PASS, PLAYLIST_TOKEN); a backup
// provider's from XTREAM2_HOST/USER/PASS (+ XTREAM2_VPN_HOST), which adds its feeds as spares.

// Real probed video quality (height/fps) keyed by streamId, so buckets sort by
// ACTUAL resolution×fps instead of the provider's unreliable "UHD"/"RAW" markers
// (many "UHD 3840P" feeds are really 1080p30 or 720p). Refreshed offline by
// scripts/probe-quality.ts; a missing entry just falls back to the name marker.
import { audioRank, qualityOf } from './quality.js'
import { jsonFile } from './fs.js'
import { askJev, type JevQuestion } from './jev.js'
import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { apiGet, BACKUP_ID, backupConfigFromEnv, claims4K, configFromEnv, guideUrl, plainCategory, readName, streamUrl, vodCatalogue, type VodFilm, type XtreamConfig } from './provider.js'

const cachePath = (name: string) => fileURLToPath(new URL(`../.cache/${name}`, import.meta.url))
/**
 * Channels seen airing live matches of each competition in the EPG (tvg-id -> competition ->
 * last-seen date), learned daily by scripts/learn-competitions.ts; read again when it changes.
 */
export const learnedFile = jsonFile<Record<string, Record<string, string>>>(() => process.env.COMPETITIONS_FILE ?? cachePath('competitions.json'), () => ({}))
/**
 * Guide channels Jev matched to ours where the name rules find nothing (myepg channel id -> our
 * tvg-ids), written by scripts/match-epg-channels.ts; read again when it changes.
 */
export const epgMatchesFile = jsonFile<Record<string, string[]>>(() => process.env.EPG_MATCHES_FILE ?? cachePath('epg-matches.json'), () => ({}))

// Provider category_ids to keep. KEEP, CAT_BUCKET, CAT_ONLY and RU_KIDS's category are this
// repo's provider's ids (recognised by HOME_CATEGORIES); on any other provider the same ids
// name other categories, so there only the name rules (autoIncluded, EXTRA_CATEGORIES) pick.
export const KEEP: number[] = [
  // UK sports — the provider ships Sky/TNT as parallel feed FAMILIES (RAW / 4K /
  // HEVC / HD copies in separate categories). Keep the high-tier ones next to the
  // HD copies so the quality sort actually has a top tier to pick from; dedupe
  // then keeps the best feed + one spare per channel.
  1731, // UK| SPORT RAW VIP DOLBY (Sky Sports 4K 3840P + RAW)
  1726, // UK| TNT SPORT RAW VIP DOLBY (TNT Ultimate 4K + TNT 1-4 RAW 50fps)
  1640, // UK| SPORT RAW (Sky Sports UHD 3840P, TNT/beIN English RAW)
  978,  // UK| SPORT HEVC
  1729, // UK| SPORT HD VIP (Sky Sports)
  1728, // UK| TNT SPORT HD VIP
  1830, // UK| TNT SPORT EVENT
  1964, // UK| NOW TV SPORT HD/RAW
  1965, // UK| NOW TV SPORT UHD
  1441, // UK| SKY SPORT+ PPV RAW
  952,  // UK| LIVE FOOTBALL PPV
  1865, // UK| EPL PREMIER LEAGUE PPV VIP
  755,  // UK| EPL PREMIER LEAGUE PPV
  769,  // UK| CHAMPIONSHIP PPV
  921,  // UK| UEFA PPV
  575,  // UK| DAZN PPV VIP
  976,  // UK| LA LIGA TEAM PPV
  977,  // UK| SERIE A TEAM PPV
  1614, // UK| LIGUE 1 PPV
  1972, // UK| SOCCER REPLAY RAW
  662,  // 4K| UHD 3840P (Sky Sports/TNT/Eleven/V Sport 4K event feeds)
  // Premier League from abroad, for matches no UK channel shows — PL channels only (CAT_ONLY)
  2032, // 8K| SPORT ON AIR (SG Hub Premier 1-11, NZ Sky Sport Premier League)
  872,  // NZ| NEW ZEALAND RAW (NZ Sky Sports Premier League 4K)
  56,   // HK| HONGKONG 4K/HD (HK Now Premier Sports 4K)
  // Tennis
  1429, // TS| TENNIS TV PPV (ATP)
  1096, // TS| TENNIS LIVE
  1927, // US| TENNIS PPV
  // UFC & fights
  1139, // US| UFC PPV
  929,  // UK| UFC PPV
  903,  // US| PPV EVENT
  380,  // UK| PPV EVENT
  // Arabic sports — beIN (pinned top) + StarzPlay / AD Sports bundle.
  // Same feed-family story as UK: beIN ships each channel as 4K / RAW / HEVC / HD
  // copies in SEPARATE categories, so keeping only the HD one (as we used to) meant
  // no FHD/4K beIN in the list at all. Highest tier first.
  1134, // AR| BEIN SPORTS 8K & 3840P (event feeds; marker trusted while idle)
  780,  // AR| BEIN SPORTS SS (numbered 4K + HD feeds)
  346,  // AR| BEIN SPORTS alternate (numbered 4K + HD feeds)
  781,  // AR| BEIN SPORTS UHD (4K/RAW/HEVC/HD feed family)
  1133, // AR| BEIN SPORTS 8K & RAW  (source-quality feeds, ~1080p50)
  349,  // AR| BEIN SPORTS 8K & HEVC
  108,  // AR| BEIN SPORTS 8K & HD
  1653, // AR| BEIN SPORTS NM   (alternate uplink — HD/RAW spares)
  1789, // AR| BEIN SPORTS BE   (backup uplink, sorts below primaries)
  1252, // AR| BEIN SPORTS SA   (HD/RAW spares)
  1736, // AR| BEIN SPORTS F & AFC
  1654, // AR| BEIN SPORTS NM & ASIA (Al Kass HD/RAW)
  24,   // AR| ARABIC SPORT 4K
  624,  // AR| UEFA CHAMPIONS LEAGUE
  2279, // AR| SPORTS PPV NM
  2347, // AR| TOD SPORTS F PPV
  2350, // AR| TOD SPORTS SA PPV
  1572, // AR| DAZN MENA PPV 8K
  548,  // AR| STARZPLAY SPORT 8K & 4K (AD Sports Premium/Fight)
  1231, // AR| STARZPLAY SPORT 8K & TK (AD Sports, Asia, Golf)
  1657, // AR| STARZPLAY SPORT M & RAW (AD Premium/Asia)
  1791, // AR| STARZPLAY SPORT BE & RAW (AD Sport, Serie A)
  2163, // AR| STARZPLAY SPORT F & RAW (StarzPlay Sports 1/2/3)
  // German sports
  333,  // DE| SPORT HD/4K
  1137, // DE| BUNDESLIGA HD/4K
  1115, // DE| BUNDESLIGA REPLAY
  667,  // DE| SKY GO SPORT VIP
  1267, // DE| WOW SPORT HD
  1633, // DE| DAZN EXCLUSIVE HD/RAW
  433,  // DE| DAZN PPV
  1672, // DE| SOCCER PPV
  2018, // DE| LEAGUES FOOTBALL PPV
  2231, // DE| RTL+ SPORT PPV RAW
  // Spanish sports
  870,  // ES| M+ LALIGA VIP
  552,  // ES| DAZN LA LIGA VIP
  1286, // ES| DAZN ESPAÑA VIP
  553,  // ES| M+ LIGA DE CAMPEONES VIP
  1601, // ES| COPA DEL REY VIP
  1616, // ES| LALIGA+ PPV VIP
  1232, // ES| LALIGA REPLAY VIP
  1287, // ES| MOVISTAR DEPORTES VIP
  1290, // ES| M+ VAMOS VIP
  1291, // ES| OTROS DEPORTES VIP
  547,  // ES| DAZN PPV
  // Italian sports
  265,  // IT| SPORT HD/4K
  476,  // IT| DAZN VIP HD/4K
  681,  // IT| SERIE A/B/C
  2242, // IT| DAZN PPV
  457,  // IT| AMAZON PRIME PPV (UCL)
  // French — Ligue 1+ (the league's own channel: 8 of 9 matches a round live only here)
  1952, // FR| LIGUE1+ VIP RAW (HD + RAW feed families)
  1954, // FR| LIGUE1+ ◉
  // Russian channels (general entertainment, isolated in their own group)
  6,    // RU| RUSSIAN HD/4K
  // Kids (live) — one provider category per language. There's no dedicated RU kids
  // category, so Russian kids are pulled by name out of category 6 (see RU_KIDS).
  304,  // UK| KIDS HD/RAW (English)
  490,  // US| KIDS HD/RAW 60fps (English)
  105,  // AR| KIDS 4K
  42,   // ES| INFANTIL VIP
  334,  // DE| KIDS HD/4K
  // US 24/7 single-show loop channels (cartoon marathons + kids/family)
  2063, // US| 24/7 CARTOON RAW 60fps
  2064, // US| 24/7 KIDS/FAMILY RAW 60fps
]

// Viewer-facing buckets, in display order — by how often each is the way in, most
// used first at stable positions: the 🏆 competition groups lead (see
// withCompetitionGroups), then the match-day trio (Sky/UK, the live-match PPV pool,
// beIN), tennis and fights (no 🏆 shortcut of their own), the other regions by
// preference, replays, non-sport, VOD.
const B = {
  uk: '🏴 Sky Sports & UK Sports',
  fbPpv: '⚽ Football — Match PPV',
  ar: '🇸🇦 beIN & Arabic Sports',
  tennis: '🎾 Tennis',
  ufc: '🥊 UFC & Fight PPV',
  ufcVod: '🎬 UFC PPV Replays (VOD)',
  de: '🇩🇪 German Sports',
  es: '🇪🇸 Spanish Sports',
  it: '🇮🇹 Italian Sports',
  fr: '🇫🇷 French Sports',
  // SG/NZ/HK Premier League channels — listed only under 🏆 Premier League
  plAbroad: '🌏 Premier League abroad',
  fbReplay: '📼 Football — Replays',
  ru: '🇷🇺 Russian Channels',
  // Every language's kids channels in one group, by language (English, Arabic, then the rest)
  kids: '🧒 Kids',
  kids247: '📺 24/7 Cartoons & Kids',
} as const
const BUCKET_ORDER: string[] = Object.values(B)

const CAT_BUCKET: Record<number, string> = {
  1731: B.uk, 1726: B.uk, 1640: B.uk, 978: B.uk,
  1729: B.uk, 1728: B.uk, 1964: B.uk, 1965: B.uk, 662: B.uk,
  2032: B.plAbroad, 872: B.plAbroad, 56: B.plAbroad,
  1830: B.fbPpv, 1441: B.fbPpv, 952: B.fbPpv, 1865: B.fbPpv, 755: B.fbPpv,
  769: B.fbPpv, 921: B.fbPpv, 575: B.fbPpv, 976: B.fbPpv, 977: B.fbPpv,
  1614: B.fbPpv, 433: B.fbPpv, 1672: B.fbPpv, 2018: B.fbPpv, 2231: B.fbPpv,
  1616: B.fbPpv, 2242: B.fbPpv, 457: B.fbPpv, 547: B.fbPpv,
  1972: B.fbReplay, 1232: B.fbReplay, 1115: B.fbReplay,
  1134: B.ar, 780: B.ar, 346: B.ar, 781: B.ar, 1133: B.ar, 349: B.ar,
  108: B.ar, 1653: B.ar, 1789: B.ar, 1252: B.ar, 1736: B.ar, 1654: B.ar,
  24: B.ar, 624: B.ar, 2279: B.ar, 2347: B.ar, 2350: B.ar, 1572: B.ar,
  548: B.ar, 1231: B.ar, 1657: B.ar, 1791: B.ar, 2163: B.ar,
  333: B.de, 1137: B.de, 667: B.de, 1267: B.de, 1633: B.de,
  870: B.es, 552: B.es, 1286: B.es, 553: B.es, 1601: B.es,
  1287: B.es, 1290: B.es, 1291: B.es,
  265: B.it, 476: B.it, 681: B.it,
  1952: B.fr, 1954: B.fr,
  1429: B.tennis, 1096: B.tennis, 1927: B.tennis,
  1139: B.ufc, 929: B.ufc, 903: B.ufc, 380: B.ufc,
  6: B.ru,
  304: B.kids, 490: B.kids, 105: B.kids, 42: B.kids, 334: B.kids,
  2063: B.kids247, 2064: B.kids247,
}

// A few of the home provider's categories, by id and exact name: two still there means KEEP's ids are its.
const HOME_CATEGORIES: Record<number, string> = {
  333: 'DE| SPORT HD/4K', 870: 'ES| M+ LALIGA VIP', 265: 'IT| SPORT HD/4K', 1429: 'TS| TENNIS TV PPV', 6: 'RU| RUSSIAN HD/4K',
}
export const isHomeProvider = (catName: Map<number, string>): boolean =>
  Object.entries(HOME_CATEGORIES).filter(([id, name]) => catName.get(Number(id)) === name).length >= 2

// More categories to list, by name (a case-insensitive regex, e.g. "^US\| NBA|^UK\| CRICKET"), on any
// provider: each is its own group, named as the provider names it, after the built-in ones.
const EXTRA_CATEGORIES = process.env.EXTRA_CATEGORIES ? new RegExp(process.env.EXTRA_CATEGORIES, 'i') : null

// Bucket for categories not in CAT_BUCKET (i.e. auto-included new ones).
// Region prefixes come as "UK|", "UK |" or "UK:" depending on the provider.
export function bucketForCategory(name: string): string {
  if (/REPLAY/i.test(name)) return B.fbReplay
  if (/UFC|PPV EVENT/i.test(name)) return B.ufc
  if (/TENNIS|ROLAND|WIMBLEDON/i.test(name)) return B.tennis
  if (/^AR\s*[|:]/i.test(name)) return B.ar
  if (/^DE\s*[|:]/i.test(name)) return /PPV/i.test(name) ? B.fbPpv : B.de
  if (/^ES\s*[|:]/i.test(name)) return /PPV/i.test(name) ? B.fbPpv : B.es
  if (/^IT\s*[|:]/i.test(name)) return /PPV/i.test(name) ? B.fbPpv : B.it
  if (/^FR\s*[|:]/i.test(name)) return /PPV/i.test(name) ? B.fbPpv : B.fr
  if (/PPV|EVENT/i.test(name)) return B.fbPpv
  return B.uk
}

// Auto-include NEW provider categories beyond KEEP: relevant region prefix +
// football/tennis/UFC topic in the name. Expired tournament-specific packages
// are deliberately blocked even if their names otherwise match a current topic.
const AUTO_REGION = /^(8K|UK|ES|DE|IT|TS|FR|AR)\s*[|:]/i
const AUTO_TOPIC =
  /FUSSBALL|UEFA|CHAMPIONS|EPL|PREMIER LEAGUE|LA ?LIGA|SERIE A|BUNDESLIGA|LIGUE ?1|FOOTBALL|SOCCER|CALCIO|COPA|TENNIS|ROLAND GARROS|WIMBLEDON|US OPEN|UFC|PPV EVENT/i
const STALE_EVENT = /WORLD CUP|MUNDIAL|ROLAND GARROS 2026/i

export function autoIncluded(categoryName: string): boolean {
  return !STALE_EVENT.test(categoryName) && AUTO_REGION.test(categoryName) && AUTO_TOPIC.test(categoryName)
}

// Language priority: EN(0) > AR(1) > DE/ES(2) > rest(3). The provider tags
// language three ways, and mixed-language groups carry all of them: a leading
// prefix (UK:/US:/ES:), a parenthetical region ("(UK) BBC"), or a trailing code
// (beIN "... MAX 5 EN" / "... 1 AR") — so an EN/AR feed sorts above DE/ES only if
// we catch all three (else German wrongly leads on its clean "DE:" prefix alone).
export function languageRank(s: string): number {
  const t = s.replace(/^8K[:|]\s*/i, '')
  if (/^(UK|US|EN)\b/i.test(t) || /\((?:UK|US|EN|GB|IE)\)/i.test(t) || /\bEN\b/.test(t)) return 0
  if (/^AR\b|عرب|العرب/i.test(t) || /\bAR\b/.test(t)) return 1
  if (/^(DE|ES)\b/i.test(t) || /\((?:DE|AT|ES)\)/i.test(t)) return 2
  return 3
}

// Tie-break at equal quality + language: demote US feeds (UK is preferred over US).
// ONLY US is moved — UK and everything else (beIN, ES, FR…) stay at 0, so no feed is
// pushed above another non-US one (an earlier version wrongly sank beIN below UK too).
// US tags appear as a prefix ("US: FOX") or a parenthetical ("4K-WC: (US) FOX").
function regionPref(s: string): number {
  return /^US\b/i.test(s) || /\(US\)/i.test(s) ? 1 : 0
}

// Leading feed-family label the provider stamps on every copy of a channel —
// region ("UK:", "ES:"), uplink ("NM:", "SS:", "SA:", "BE:", "F:", "◉:") or
// quality branding ("8K:", "ᵁᴴᴰ:", "VIP:"). Matched structurally because the
// provider keeps adding new families.
const FEED_PREFIX = /^[^\s:|]{1,6}[:|]\s*/

// Superscript/modifier letters the provider spells feed markers in (ᴿᴬᵂ, ᵁᴴᴰ ³⁸⁴⁰ᴾ,
// ʰᵉᵛᶜ, ᴬʳᵉⁿᵃ) mapped to ascii, so they become ordinary words to strip or keep.
const SUP_FROM = 'ᴬᴮᶜᴰᴱᶠᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿˢᵀᵁⱽᵂᵃᵇᵈᵉᵍʰⁱʲᵏˡᵐⁿᵒᵖʳᵗᵘᵛʷˣʸᶻ⁰¹²³⁴⁵⁶⁷⁸⁹'
const SUP_TO = 'abcdefghijklmnoprstuvwabdeghijklmnoprtuvwxyz0123456789'
const SUP_RE = new RegExp(`[${SUP_FROM}]`, 'g')
const unsuper = (s: string) => s.replace(SUP_RE, (ch) => SUP_TO[SUP_FROM.indexOf(ch)])
// Words that only tell feeds of one channel apart: quality, codec, fps, uplink, delivery.
const FEED_WORDS = new Set([
  '8k', '4k', 'hd', 'sd', 'fhd', 'uhd', 'hdr', 'raw', 'vip', 'hevc', 'fps', '50', '60',
  '3840p', '2160p', '1080p', '720p', 'mobil', 'mobile', 'sat', 'dolby', 'audio', 'bk', 'bk1', 'bk2', 'stz', 'stc',
])
/** One identity for all parallel feeds of a channel: family prefix, feed words and
 *  plural dropped — "VIP: TNT SPORTS 1 ᴿᴬᵂ ⁵⁰ FPS" and "NOW: TNT SPORT 1 ᴴᴰ" → "tnt sport 1". */
export const chanKey = (s: string): string =>
  unsuper(s.replace(FEED_PREFIX, ''))
    .toLowerCase()
    .replace(/⚽/g, 'o')
    .replace(/[^a-z0-9\u0600-\u06ff]+/g, ' ')
    .split(' ')
    .filter((t) => t && !FEED_WORDS.has(t))
    .map((t) => t.replace(/s$/, ''))
    .join(' ')

// Quality from name markers (the API has no real res/fps metadata).
// 0 = explicit SD/LQ -> dropped. Higher = listed first within each bucket.
// Channel-name markers win; group name is the fallback for unmarked channels.
export function qualityScore(channelName: string, groupName: string): number {
  const score = (s: string): number | null => {
    if (/\bSD\b|ˢᵈ|▼|\bLQ\b/i.test(s)) return 0
    let v: number | null = null
    if (/⁸ᴷ|ᵁᴴᴰ|UHD|4K|³⁸⁴⁰|3840|2160/i.test(s)) v = 5
    else if (/FHD|ᶠᴴᴰ|1080/i.test(s)) v = 4
    else if (/ᴿᴬᵂ|\bRAW\b/i.test(s)) v = 4 // source-quality feed, typically 1080p50
    else if (/ʰᵉᵛᶜ|\bHEVC\b/i.test(s)) v = 4 // HEVC feeds here run ~1080p — rank above plain HD
    else if (/ᴴᴰ|\bHD\b/i.test(s)) v = 3
    if (/(⁶⁰|⁵⁰)\s*ᶠᵖˢ|(60|50)\s*FPS/i.test(s)) v = (v ?? 3) + 1
    return v
  }
  // Strip the feed-family branding prefix so "ᵁᴴᴰ: beIN 1 HD" is scored as HD,
  // not UHD. The actual channel marker must win over its source family's name.
  return score(channelName.replace(FEED_PREFIX, '')) ?? score(groupName) ?? 2
}

// ffprobe reads r_frame_rate, which on some feeds is the container TICK rate rather
// than the display rate. 100 is a genuine reading, but it is the FIELD rate of 50p
// sports feeds — the same picture as 50 — so it ranks as 50 rather than outranking
// every 50p feed. 120/240/250/300 only ever show up on 24-30fps material
// (nursery-rhyme loops, documentary channels), where it was floating them above
// genuine 60fps channels, so anything above 100 is treated as unknown.
export const saneFps = (fps: number): number =>
  !Number.isFinite(fps) || fps <= 0 || fps > 100 ? 0 : fps === 100 ? 50 : fps

// Resolution/fps that order feeds (ahead of the marker tier, which only breaks ties).
// Probed value when we have it — so the genuinely-highest-res stream floats up — else
// the marker's NOMINAL resolution, capped at 1080p: only ~1 in 6 "4K"-named 24/7 feeds
// probes at 2160p (2026-11-01), so an unverified "4K" ranks as an unverified 1080p and
// any verified 1080p feed (real fps > 0) beats it. Event feeds keep the full nominal.
//
// trustMarker (PPV slots + "(EVENT ONLY)" feeds): these idle or show low-res filler
// between matches, so a probe BELOW the marker is that filler, not live quality —
// fall back to the nominal tier (fps unknown) instead of demoting it. A probe AT/ABOVE
// the marker is genuine live quality and is always kept.
//
// "(MOBIL)"/"(MOBILE)" and provider-labelled "720P" feeds are adaptive restreams: the
// same feed reads 540p25 one moment and 1080p50 the next, and the cache keeps the peak,
// so their probe is capped at the 720p25 they mostly deliver — below every steady 1080p feed.
const MOBILE = /\(MOBILE?\)|720P/i
function realRes(streamId: number, markerQ: number, trustMarker: boolean, name: string): { h: number; fps: number } {
  const nominal = markerQ >= 5 ? 2160 : markerQ === 4 ? 1080 : markerQ === 3 ? 720 : markerQ === 2 ? 540 : 360
  const m = qualityOf(streamId)
  if (!m) return { h: trustMarker ? nominal : Math.min(nominal, 1080), fps: 0 }
  if (trustMarker && m.h < nominal) return { h: nominal, fps: 0 }
  const r = { h: m.h, fps: saneFps(m.fps) }
  return MOBILE.test(name) ? { h: Math.min(r.h, 720), fps: Math.min(r.fps, 25) } : r
}

// Atmos over 5.1 over stereo — breaks ties between equal-res/fps feeds (a centre channel
// carries the commentary, so a soundbar can lift it over the crowd).
const audio = (streamId: number): number => audioRank(qualityOf(streamId))

interface Category {
  category_id: string
  category_name: string
}

interface Stream {
  stream_id: number
  name: string
  stream_icon?: string
  epg_channel_id?: string | null
  category_id: string
}

export interface Channel {
  streamId: number
  name: string
  logo: string
  tvgId: string
  group: string
  /** PPV/event slot — channel name carries the event, gets synthetic EPG */
  isEventSlot: boolean
  /** marker-based quality tier (qualityScore): 0=SD … 4=FHD/RAW, 5=UHD/8K; unset for VOD */
  q?: number
  /** competitions carried per name/category (COMPS keys); EPG-learned ones are added at grouping time */
  comps?: string[]
  /** VOD container extension (mkv/mp4) — served from /movie/ instead of /live/ */
  vodExt?: string
  /** A provider-B feed (withBackupFeeds); its streamId is BACKUP_ID + B's own id. */
  backup?: true
}

const EVENT_GROUP = /PPV|EVENT|REPLAY/i
// Decorative separator entries like "##### beIN SPORTS #####". Two is enough —
// the provider also ships the "## NOW TV SPORT ᵁᴴᴰ ##" form, which a 3+ rule missed.
const SEPARATOR = /^[#*=─—-]{2,}.*[#*=─—-]{2,}$/
// Finished/idle event slots — they reappear when renamed for the next event.
// Two shapes: a leading status tag ("End | Panthers @ Cardinals | …"), and an
// idle notice anywhere in the name ("AR: DAZN PPV 23 - NO EVENT STREAMING - |
// 8K EXCLUSIVE"), which the leading-tag form misses.
const DEAD_SLOT = /^[\s#]*\b(END(?:ED)?|FINISHED|OFF\s*AIR|NO\s+EVENTS?|TBA)\b\s*[|:.-]?|\bNO\s+EVENT\b|\bNO\s+STREAM(?:ING)?\b/i
// Backup source groups sort below primaries within the same bucket
const BACKUP_GROUP = /ᴮᴱ|ᴮᴷ|⁽ᴮᴷ⁾|\(BK\)|BACKUP/i
// beIN pins to the top of the Arabic bucket — the only AR channel that matters.
// Matched on the CATEGORY as well as the channel, because beIN's 4K event slots are
// named for the event ("NM: SPORT 2 ⁴ᴷ ³⁸⁴⁰ᵖ") rather than the brand, and a
// name-only test buried those genuine 2160p feeds below 720p beIN ones.
const BEIN = /bein/i
// Feeds that only carry signal during an event (beIN's 4K slots, mostly). They
// idle or show low-res filler in between, so a probe below their marker is that
// filler, not the feed — trust the marker instead (see realRes).
export const EVENT_ONLY = /\(\s*EVENT\s*ONLY\s*\)/i
// The provider stamps beIN MENA ids wrongly: Arabic beIN 1-9 feeds carry the English channels'
// ids (beINSports3En.qa: there is no English beIN 3-9, so no guide has them), and a French feed
// can carry Arabic beIN's id. Arabic feeds get beINSportsN.qa; the French one is left untagged
// so it takes its French twin's id.
function beinEpgId(id: string, name: string): string {
  const en = id.match(/^beINSports(\d)En\.qa$/)
  if (en && !/\bENG(LISH)?\b|\bEN\b/i.test(name)) return `beINSports${en[1]}.qa`
  if (/^beINSports\d\.qa$/.test(id) && /\bFRANCE\b|\bFRENCH\b|\bFR\b/i.test(name)) return ''
  return id
}
// Mixed categories we take only some channels from. Cat 662 (4K| UHD 3840P) mixes 4K
// sport feeds (Sky/TNT/Eleven/ESPN/V Sport) with lifestyle/shopping/news 4K (Cocina,
// QVC, Museum, Bloomberg…) — keep only sport. The SG/NZ/HK ones are there for Premier
// League matches no UK channel shows — keep only their PL channels.
const CAT_ONLY: Record<number, RegExp> = {
  662: /sport|tnt|espn|bein|eleven|dazn/i,
  2032: /^SG: HUB PREMIER|^NZ: SKY SPORTS? PREMIER LEAGUE/i,
  872: /PREMIER LEAGUE/i,
  56: /NOW PREMIER SPORTS/i,
}
// The provider's own mistakes, checked against each feed's picture (2026-11-05): "PEH TB" is РЕН ТВ
// spelt in Latin look-alikes, the same feed as "REN TV", whose logo is Petersburg's 5 Kanal; "PYATIT
// KANAL" is Ukraine's 5 Kanal (5.ua), not Russia's, under Tulsky TV's logo.
const REN_TV = { name: 'RU: REN TV', logo: 'http://photo-tmdb.com/stalker_portal/logos/PEH-TB.jpg' }
const PROVIDER_FIX: Record<string, { name: string; logo: string }> = {
  'RU: PEH TB': REN_TV,
  'RU: REN TV': REN_TV,
  'RU: PYATIT KANAL': { name: 'RU: 5 KANAL UKRAINE', logo: 'https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/ukraine/channel5-ua.png' },
}

// Russian kids channels (Nick/Cartoon Network/Disney) sit in the general RU
// category — no dedicated RU kids category exists — so route them out by name.
const RU_KIDS = /nick|cartoon\s*network|disney\s*(?:channel|jr|junior|xd)|карусел|мульт|малыш/i

// Dedupe key: same cleaned name (+ quality tier, when given) counts as a dup.
// Omit `tier` to collapse a channel across all its qualities (used where one
// channel ships at many tiers).
function dedupeKey(name: string, tier?: number): string {
  const cleaned = name
    .replace(FEED_PREFIX, '')
    .toLowerCase()
    .replace(/\b(4k|uhd|fhd|hd|sd|raw|hevc|vip|stc|stz)\b/g, '')
    .replace(/[^a-z0-9؀-ۿ]+/g, ' ')
    .trim()
  return tier === undefined ? cleaned : `${cleaned}|${tier}`
}

// Sort key that keeps a channel's parallel feeds together and in sequence (FOX 1/2,
// 4K 1/2/3) when their quality ties, instead of letting them scatter. Strips the
// region prefix, non-ascii markers and quality words; compared with localeCompare
// {numeric} so "2" sorts before "10".
const nameKey = (s: string): string =>
  s.replace(FEED_PREFIX, '')
    .replace(/[^\p{ASCII}]/gu, ' ')
    .replace(/\b(8k|4k|uhd|fhd|hd|sd|raw|hevc|vip)\b/gi, '')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase()

// --- Competitions ------------------------------------------------------------
// "🏆 <competition>" groups gather every channel carrying a competition across
// regions. A channel qualifies by NAME ("SKY SPORT BUNDESLIGA 1"), by provider
// CATEGORY (the per-league PPV packages), or — for 24/7 channels — by having aired
// a live match of it recently per the EPG (learned into .cache/competitions.json by
// scripts/learn-competitions.ts: the guides only reach ~36h ahead, far too short
// to see a cup round or a UEFA week directly). Vetoes keep out other sports and
// women's/second-tier competitions that reuse the names (volleyball "Serie A",
// "Frauen-Bundesliga", "LaLiga Hypermotion", "Brasileiro Serie A").
// Display order: the weekly competitions by region preference (UK, ES, DE, IT), then
// the secondary UEFA cups, then domestic cups. Groups that exist only while the EPG
// has seen a round recently (FA Cup, EFL Cup, Coppa, Pokal) trail the block, so their
// coming and going never shifts the groups everyone uses.
/** [logo]: ESPN's league logo (a.espncdn.com/i/leaguelogos/soccer/<logo>.png), its dark-ground set's; Coppa Italia's has none, and its usual one is a white patch. */
interface Competition { key: string; group: string; logo?: string; re: RegExp; not?: RegExp }
export const COMPS: Competition[] = [
  { key: 'pl', group: '🏆 Premier League', logo: '500-dark/23', re: /premier league|\bEPL\b|\bsky sports? pl\b|hub premier|now premier sports|الدوري الإنجليزي الممتاز/i, not: /scottish|welsh|irish|premier league 2\b|\bpl2\b|premier league\s*[-–—]\s*(?![^|]*\s(?:v|vs\.?)\s)[^|\d]+(?:\||$)/i },
  { key: 'ucl', group: '🏆 Champions League', logo: '500-dark/2', re: /champions league|liga de campeones|ligue des champions|دوري أبطال أوروبا|دوري الأبطال|\bUCL\b/i, not: /afc champions|caf champions|concacaf|asian champions|global champions|gulf club champions/i },
  { key: 'laliga', group: '🏆 La Liga', logo: '500-dark/15', re: /\bla ?liga\b|الدوري الإسباني/i, not: /hypermotion|smartbank|segunda|liga portugal|liga mx|liga f\b/i },
  { key: 'bundes', group: '🏆 Bundesliga', logo: '500-dark/10', re: /bundesliga|الدوري الألماني/i, not: /2\.\s*bundesliga|zweite|regionalliga|3\. liga|austria|österreich|admiral/i },
  { key: 'seriea', group: '🏆 Serie A', logo: '500-dark/12', re: /\bserie a\b|الدوري الإيطالي/i, not: /brasileir|betano|serie a2|serie [bc]\b|girone|lega pro|primavera/i },
  { key: 'ligue1', group: '🏆 Ligue 1', logo: '500-dark/9', re: /ligue ?1\b|الدوري الفرنسي/i },
  { key: 'uel', group: '🏆 Europa League', logo: '500-dark/2310', re: /europa league|liga europa|ligue europa|الدوري الأوروبي|\bUEL\b/i },
  { key: 'uecl', group: '🏆 Conference League', logo: '500-dark/20296', re: /conference league|دوري المؤتمر|\bUECL\b/i },
  { key: 'champ', group: '🏆 Championship', logo: '500-dark/24', re: /^champ:|(?:efl|sky bet|english)\s+championship|championship ppv/i },
  { key: 'cdr', group: '🏆 Copa del Rey', logo: '500-dark/80', re: /copa del rey|كأس ملك إسبانيا|كأس الملك الإسباني/i, not: /barcos|baloncesto/i },
  { key: 'facup', group: '🏆 FA Cup', logo: '500-dark/40', re: /\bFA Cup\b|كأس الاتحاد الإنجليزي/i, not: /youth|trophy|vase|scottish|welsh|irish/i },
  { key: 'eflcup', group: '🏆 EFL Cup', logo: '500-dark/41', re: /carabao|\bEFL Cup\b|\bleague cup\b|كأس الرابطة الإنجليزية|كأس كاراباو/i, not: /adib|\buae\b|scottish|welsh|irish|premier sports cup/i },
  { key: 'coppa', group: '🏆 Coppa Italia', logo: '500/2192', re: /coppa italia|كأس إيطاليا/i, not: /serie c|primavera/i },
  { key: 'pokal', group: '🏆 DFB-Pokal', logo: '500-dark/2061', re: /dfb[- ]?pokal|كأس ألمانيا/i },
]
const UEFA_ALL = ['ucl', 'uel', 'uecl']
// Shared vetoes: other sports and women's competitions that reuse league names, and
// non-match programming (a channel airing "Premier League Review" isn't showing it).
const OTHER_SPORT = /basket|handball|pallamano|volley|superlega|hockey|rugby|darts|netball|cricket|snooker|golf|tennis|boxing|drone|horse|esport|virtual|vela\b/i
const WOMEN = /women|femen|frauen|femminile|f[ée]minin|\bwsl\b|uwcl|سيدات|نساء/i
const SHOW = /highlight|hoogtepunten|review|\bshow\b|magazin|stories|\bbest\b|top goals|top \d+|goals? of|legend|classic|rewind|preview|previa|pre-?match|vorbericht|anteprima|analys|resumen|resúmen|zusammenfassung|replay|re-live|wiederholung|special|especial|feature|weekly|daily|\bnews\b|press conference|conferenza|rueda de prensa|pressekonferenz|weigh|goal rush|greatest|documentar|\bfilm\b|pel[ií]cula|countdown|build-?up|warm-?up|inside|kompakt|\bpur\b|retro|netbusters|full impact|\bicons?\b|league 100\b|\bep\.? ?\d|episode|folge/i
const vetoed = (text: string) => OTHER_SPORT.test(text) || WOMEN.test(text) || SHOW.test(text)
// A programme that is a live match: a "live" tag or a fixture ("X v Y", "X - Y", "X vs Y", Arabic "X × Y").
const LIVE = /\blive\b|ᴸᶦᵛᵉ|\ben directo\b|\ben vivo\b|\bdiretta\b|\ben direct\b|مباشر|прямая трансляция/i
const FIXTURE = /\S\s+(?:v|vs\.?|x|×|-|–|—|@)\s+\S|\S×\S/i
// "Next | Deportivo vs. Osasuna | … 20:00 (GMT)": an event channel announcing a match, not airing it.
const ANNOUNCED = /^\s*next\b/i

/** Competitions a channel carries by its own name + provider category. A bare
 *  "UEFA" package (UK| UEFA PPV) can't tell UCL from UEL/UECL — it joins all three. */
export function staticCompetitions(name: string, category: string): string[] {
  if (vetoed(name)) return []
  const keys = COMPS.filter(
    (c) => (c.re.test(name) || c.re.test(category)) && !c.not?.test(name) && !c.not?.test(category),
  ).map((c) => c.key)
  return keys.length || !/\bUEFA\b/i.test(category) ? keys : UEFA_ALL
}

// A season tag from before last season ("EFL Cup 07/08", "Goal Rush 2017/18") marks a retro replay.
const seasonYear = (title: string): number | null => {
  const m = title.match(/\b(\d{4}|\d{2})\/\d{2,4}\b/)
  return m ? (m[1].length === 4 ? Number(m[1]) : 2000 + Number(m[1])) : null
}

/** Whether a programme title could be a current live match: live-tagged or a fixture, no veto,
 *  no announcement of a later one, no retro season. Only these need their competition decided. */
export function couldBeLiveMatch(title: string, now = new Date()): boolean {
  if (vetoed(title) || ANNOUNCED.test(title) || !(LIVE.test(title) || FIXTURE.test(title))) return false
  const season = seasonYear(title)
  return season === null || season >= now.getFullYear() - 1
}

/** Competitions a programme title proves a channel carries — live matches only. */
export function learnedCompetitions(title: string, now = new Date()): string[] {
  if (!couldBeLiveMatch(title, now)) return []
  return COMPS.filter((c) => c.re.test(title) && !c.not?.test(title)).map((c) => c.key)
}

// Re-runs. A match airs again and again (beIN repeats a fixture four times a day, club TV every few
// hours) under the very title of the live airing, so the title can't tell them apart; the guide can.
// A title's fixture is its two teams: the team word just before and just after its "v" (else its last
// " - ", since templates like "Liga włoska - mecz: X - Y" put the teams last). Club words (Real, City,
// United…) and competition words are skipped, so "Real Madrid v Real Betis" is madrid|betis.
// After a match: words that only follow a played match.
const AFTER = /highlight|hoogtepunten|resumen|resúmen|zusammenfassung|\breview\b|rewind|replay|re-live|wiederholung|shortcut|\bsintesi\b|\bresume\b|résumé|ملخص/i
const VERSUS = /\s(?:v|vs\.?|x|×|@)\s/i
const DASH = /\s[-–—]\s/g
const NOT_TEAM = new Set(('fc cf ac as sc sv ssc afc cd ud sd rc rcd vfb vfl tsg rb bc club de del la el le the of and real city united ' +
  'athletic atletico sporting borussia inter olympique stade calcio football fussball soccer futbol ' +
  'premier league liga laliga bundesliga serie ligue cup copa coppa pokal champions europa conference uefa efl fa ' +
  'live match matchday jornada spieltag giornata journee highlights full english tv hd uhd ea sports').split(' '))
export const teamWords = (s: string): string[] =>
  s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().split(/[^\p{L}]+/u).filter((w) => w.length > 2 && !NOT_TEAM.has(w))

/** The fixture a title names, as its two teams in a fixed order ("arsenal|liverpool"), or null. */
function fixtureOf(title: string): string | null {
  const v = title.match(VERSUS)
  const at = v ? v.index! : [...title.matchAll(DASH)].at(-1)?.index
  if (at === undefined) return null
  const left = teamWords(title.slice(0, at)).at(-1)
  const right = teamWords(title.slice(at + 1).replace(/^\S+/, '')).at(0)
  return left && right ? [left, right].sort().join('|') : null
}

export interface Airing { tvgId: string; title: string; start: number; stop: number }

// Only an airing a whole match long can be the one that aired it first; a 15-minute preview titled with the fixture isn't.
const FULL_MATCH_SEC = 90 * 60
// Re-runs follow within a day or two, while the same clubs can meet again days later in another competition.
const LOOKBACK_SEC = 48 * 3600
// The competitions a title names; two titles naming different ones are different matches (cup tie, then league game).
// Youth sides play the same fixture the same day ("UEFA Youth League: AEK - Real Madrid" in the morning).
const YOUTH = /youth league|\bU-?(?:17|19|21|23)\b|primavera|juvenil/i
const compsOf = (title: string) => (YOUTH.test(title) ? ['youth'] : COMPS.filter((c) => c.re.test(title) && !c.not?.test(title)).map((c) => c.key))
const sameComp = (a: string[], b: string[]) => !a.length || !b.length || a.some((k) => b.includes(k))

/**
 * Which of [airings] (indexes) are re-runs of a match: each airing [couldBeLiveMatch] whose fixture
 * already aired — it starts after the fixture's first airing ended, or after a highlights show of it
 * began. Airings of a fixture that overlap its first one (the same match live on several channels)
 * stay live, and so does anything tagged live. Back-to-back blocks of one title on one channel (event
 * channels cut a match into hours) are one airing; announcements of a match ([ANNOUNCED]) aren't one.
 * Unsure means live: a re-run left in costs a row, a live match marked a re-run would hide it.
 */
export function replayAirings(airings: Airing[], now = new Date()): Set<number> {
  type Run = { members: number[]; title: string; start: number; stop: number; post: boolean; comps: string[] }
  const byShow = new Map<string, number[]>()
  airings.forEach((a, i) => {
    if (!FIXTURE.test(a.title) || ANNOUNCED.test(a.title)) return
    const key = `${a.tvgId}\n${a.title}`
    ;(byShow.get(key) ?? byShow.set(key, []).get(key)!).push(i)
  })
  const byFixture = new Map<string, Run[]>()
  for (const members of byShow.values()) {
    const { title } = airings[members[0]]
    const fixture = fixtureOf(title)
    const post = !couldBeLiveMatch(title, now)
    if (!fixture || (post && !AFTER.test(title))) continue
    const runs = byFixture.get(fixture) ?? byFixture.set(fixture, []).get(fixture)!
    const comps = compsOf(title)
    let run: Run | null = null
    for (const i of members.sort((x, y) => airings[x].start - airings[y].start)) {
      const a = airings[i]
      if (run && a.start <= run.stop) {
        run.members.push(i)
        run.stop = Math.max(run.stop, a.stop)
      } else runs.push((run = { members: [i], title, start: a.start, stop: a.stop, post, comps }))
    }
  }
  const replays = new Set<number>()
  for (const runs of byFixture.values()) {
    for (const m of runs) {
      if (m.post || LIVE.test(m.title)) continue
      let first = m
      let highlightsFrom = Infinity
      for (const p of runs) {
        if (p === m || p.start < m.start - LOOKBACK_SEC || !sameComp(m.comps, p.comps)) continue
        if (p.post) highlightsFrom = Math.min(highlightsFrom, p.start)
        else if (p.start < first.start && p.stop - p.start >= FULL_MATCH_SEC) first = p
      }
      if (m.start >= first.stop || m.start > highlightsFrom) for (const i of m.members) replays.add(i)
    }
  }
  return replays
}

// Jev's options: the group names, terse (every option is billed in every question), with a hint
// only where a name is ambiguous, plus two catch-alls.
const JEV_HINTS: Record<string, string> = {
  laliga: "Spain's top division", bundes: "Germany's top division", seriea: "Italy's top division",
  champ: "England's second tier", eflcup: 'Carabao Cup',
}
const jevLabel = (c: Competition) => c.group.replace('🏆 ', '')
const JEV_COMPS: Record<string, string | null> = Object.fromEntries([
  ...COMPS.map((c) => [jevLabel(c), JEV_HINTS[c.key] ?? null]),
  ['other football', 'other leagues or divisions, national teams, friendlies'],
  ['not football', null],
])

/**
 * Jev's verdict on match-like titles (couldBeLiveMatch), for the daily learner. The regexes
 * miss team-only fixtures and other languages, and match shows that look like fixtures. On 852
 * hand-labelled guide titles (EN/DE/ES/IT/FR/AR/RU) this scored precision 0.96 and recall 0.92;
 * the regexes scored 0.63 and 0.42. Throws if Jev is unavailable.
 */
export async function jevCompetitions(titles: string[]): Promise<Map<string, string[]>> {
  const questions: Record<string, JevQuestion> = {}
  titles.forEach((title, i) => {
    const t = JSON.stringify(title)
    questions[`c${i}`] = { type: 'choice', instructions: `Competition of the TV programme ${t}?`, criteria: JEV_COMPS }
    questions[`m${i}`] = { type: 'noul', instructions: `Is the TV programme ${t} one football match itself (live or in full), not highlights, a show, a preview or a past-season classic?` }
    questions[`w${i}`] = { type: 'noul', instructions: `Is ${t} women's, youth or reserve football?` }
  })
  const a = await askJev('Sports TV guide (EPG) programme titles.', questions)
  return new Map(titles.map((title, i) => {
    const { choice = '', probabilities = {} } = a[`c${i}`]
    const comp = COMPS.find((c) => jevLabel(c) === choice)
    const yes = comp && probabilities[choice] >= 0.8 && a[`m${i}`].noul! >= 0.6 && a[`w${i}`].noul! < 0.5
    return [title, yes ? [comp.key] : []]
  }))
}

// UFC PPV / Fight-Night replays live in the VOD catalog — the live PPV slots go
// black once the event window passes. The provider files them under many
// language prefixes ("EN -", "ES -", "SOC -") and sometimes mis-categorizes them
// (e.g. Freedom 250 sits under "NORDIC SPORT"), so match by NAME across the whole
// VOD list, collapse language/date variants to one entry per event (English
// first, newest upload), and serve them on-demand. The number identifies the card.
const UFC_VOD_EVENT = /\bUFC\s+(FREEDOM\s+|FIGHT\s*NIGHT\s+|ON\s+\w+\s+)?(\d{1,3})\b/i

function ufcReplays(vod: VodFilm[]): Channel[] {
  type V = { c: Channel; key: string; lang: number; added: number }
  const items: V[] = []
  for (const s of vod) {
    const name = s.name
    const m = name.match(UFC_VOD_EVENT)
    if (!m) continue
    items.push({
      key: (m[1] ?? '').toLowerCase().replace(/\s+/g, '') + m[2],
      lang: languageRank(name),
      added: s.added,
      c: {
        streamId: s.id,
        name,
        logo: s.icon,
        tvgId: '',
        group: B.ufcVod,
        isEventSlot: false,
        vodExt: s.ext,
      },
    })
  }
  items.sort((a, b) => a.lang - b.lang || b.added - a.added)
  const seen = new Set<string>()
  const out: Channel[] = []
  for (const it of items) {
    if (seen.has(it.key)) continue
    seen.add(it.key)
    out.push(it.c)
  }
  return out
}

// --- 4K/UHD movies (VOD) ---------------------------------------------------
// The provider marks movie quality on the CATEGORY name, not per title (see claims4K). We take
// every such category — all languages — as on-demand VOD so the playlist gains a clean,
// browsable 4K film library beside the sports.

// "NETFLIX MOVIES ⁴ᴷ ³⁸⁴⁰ᴾ ᴰᵒˡᵇʸ ⱽᶦˢᶦᵒⁿ" -> "🎬 NETFLIX MOVIES 4K".
export function movieGroup(cat: string): string {
  return `🎬 ${plainCategory(cat)} 4K`
}

// Every movie in a 4K-marked category, as on-demand VOD (served from /movie/).
// No tvg-id, so buildEventEpg gives them no (bogus) EPG row.
function movie4kChannels(vod: VodFilm[], cats: Record<string, string>): Channel[] {
  const out: Channel[] = []
  for (const s of vod) {
    const cat = cats[s.cat]
    if (!cat || !claims4K(cat)) continue
    const name = readName(s.name).title
    if (!name || SEPARATOR.test(name)) continue
    out.push({
      streamId: s.id,
      name,
      logo: s.icon,
      tvgId: '',
      group: movieGroup(cat),
      isEventSlot: false,
      vodExt: s.ext,
    })
  }
  return out
}

/** The on-demand entries (4K movies, UFC replays), from the saved VOD catalogue (refreshed daily, never per build). */
export async function vodChannels(cfg: XtreamConfig): Promise<Channel[]> {
  const { cats, films } = await vodCatalogue(cfg)
  return [...ufcReplays(films), ...movie4kChannels(films, cats)]
}

// The guide's logos (MyepgGuide.icons), saved by each guide build: the playlist and the app's
// channel list never pull the guide themselves, and a restart would otherwise lose them.
const EPG_ICONS = new URL('../.cache/epg-icons.json', import.meta.url)

export async function saveEpgIcons(icons: Map<string, string>): Promise<void> {
  if (icons.size) await writeFile(EPG_ICONS, JSON.stringify(Object.fromEntries(icons))).catch(() => {})
}

/** Fetch + filter provider channels into ordered, quality-sorted buckets. `vod: false`
 *  leaves out the on-demand entries, for the EPG. */
export async function fetchCuratedChannels(cfg: XtreamConfig, vod = true): Promise<Channel[]> {
  const [cats, streams, vodList, icons] = await Promise.all([
    apiGet<Category[]>(cfg, 'get_live_categories'),
    apiGet<Stream[]>(cfg, 'get_live_streams'),
    vod ? vodChannels(cfg).catch(() => []) : [],
    readFile(EPG_ICONS, 'utf8').then((j) => new Map<string, string>(Object.entries(JSON.parse(j)))).catch(() => new Map<string, string>()),
  ])
  const catName = new Map(cats.map((c) => [Number(c.category_id), c.category_name]))
  const byCat = new Map<number, Stream[]>()
  for (const s of streams) {
    const id = Number(s.category_id)
    const list = byCat.get(id)
    if (list) list.push(s)
    else byCat.set(id, [s])
  }

  const home = isHomeProvider(catName)
  const keep = home ? KEEP : []
  const keepSet = new Set(keep)
  // Without KEEP, a region's general sport categories ("UK| SPORTS HD") count too: KEEP holds the home ones.
  const auto = (name: string) => autoIncluded(name) || (!home && AUTO_REGION.test(name) && /SPORT/i.test(name) && !STALE_EVENT.test(name))
  const autoIds = [...catName.entries()]
    .filter(([id, name]) => !keepSet.has(id) && (auto(name) || EXTRA_CATEGORIES?.test(name)))
    .map(([id]) => id)

  type Item = { c: Channel; q: number; rh: number; rf: number; lang: number; region: number; backup: number; bein: number; nkey: string; ck: string; idx: number }
  const byBucket = new Map<string, Item[]>()

  const ids = [...keep, ...autoIds]
  for (const [idx, id] of ids.entries()) {
    const group = catName.get(id)
    if (!group) continue
    const bucket = (home ? CAT_BUCKET[id] : undefined) ?? (auto(group) ? bucketForCategory(group) : group)
    const isEventSlot = EVENT_GROUP.test(group)
    const backup = BACKUP_GROUP.test(group) ? 1 : 0
    for (const s of byCat.get(id) ?? []) {
      const fix = PROVIDER_FIX[String(s.name ?? '').trim()]
      const name = fix?.name ?? String(s.name ?? '').trim()
      if (!name || SEPARATOR.test(name)) continue
      if (home && CAT_ONLY[id] && !CAT_ONLY[id].test(name)) continue
      if (isEventSlot && DEAD_SLOT.test(name)) continue
      const q = qualityScore(name, group)
      if (q === 0) continue // explicit SD/LQ feeds
      // Russian kids channels ride in the general RU category — route them to the kids group.
      const chBucket = home && id === 6 && RU_KIDS.test(name) ? B.kids : bucket
      // Feeds that only light up during a match (PPV slots, beIN's "(EVENT ONLY)"
      // 4K slots) keep their marker tier when probed low — that's idle filler.
      const { h: rh, fps: rf } = realRes(s.stream_id, q, isEventSlot || EVENT_ONLY.test(name), name)
      // Event slots get a synthetic id served by /api/epg; others use provider EPG ids
      const tvgId = isEventSlot ? `ppv.${s.stream_id}` : beinEpgId(String(s.epg_channel_id ?? ''), name)
      const item: Item = {
        q,
        rh,
        rf,
        lang: languageRank(name),
        region: regionPref(name),
        backup,
        bein: BEIN.test(name) || BEIN.test(group) ? 0 : 1,
        nkey: nameKey(name),
        ck: chanKey(name),
        idx,
        c: {
          streamId: s.stream_id,
          name,
          logo: fix?.logo ?? icons.get(name) ?? icons.get(tvgId) ?? String(s.stream_icon ?? ''),
          tvgId,
          group: chBucket,
          isEventSlot,
          q,
          comps: staticCompetitions(name, group),
        },
      }
      const list = byBucket.get(chBucket)
      if (list) list.push(item)
      else byBucket.set(chBucket, [item])
    }
  }

  const channels: Channel[] = []
  for (const bucket of new Set([...BUCKET_ORDER, ...byBucket.keys()])) {
    const items = byBucket.get(bucket)
    if (!items) continue
    // beIN first in the Arabic bucket, then by EFFECTIVE resolution/fps — the probed
    // value where we have one, else the marker's nominal tier (realRes already makes
    // that substitution), so an unprobed or idle "4K" feed still ranks as 4K while a
    // feed MEASURED at 720p stops outranking a measured 1080p50 one just because the
    // provider stamped "4K" on its name. Marker tier only breaks ties from there, then
    // language, US demoted below UK, primaries before backups, and finally sibling
    // feeds grouped + numbered (FOX 1/2, 4K 1/2/3) so equal feeds don't scatter.
    const isArabic = bucket === B.ar
    const isKids = bucket === B.kids
    items.sort(
      (a, b) =>
        (isArabic ? a.bein - b.bein : 0) || (isKids ? a.lang - b.lang : 0) ||
        b.rh - a.rh || b.rf - a.rf || audio(b.c.streamId) - audio(a.c.streamId) || b.q - a.q ||
        a.lang - b.lang || a.region - b.region || a.backup - b.backup ||
        a.nkey.localeCompare(b.nkey, 'en', { numeric: true }) || a.idx - b.idx,
    )
    // The bar is FHD+: a sub-1080p copy of a channel that also has a 1080p+ feed here
    // is redundant, so it goes — HD stays only where nothing better exists. Judged on
    // EFFECTIVE height (probed where known), so a feed labelled "HEVC 4K" that really
    // runs 720p is the copy that goes, and an unlabelled feed probed at 1080p50 stays.
    // Kids holds every language: "DE: CARTOON NETWORK" is not a copy of the UK one (US and UK are one).
    const lang = (it: Item) => (isKids ? (it.c.name.match(FEED_PREFIX)?.[0] ?? '').replace(/^US/i, 'UK') : '')
    const bestRes = new Map<string, number>()
    for (const it of items) bestRes.set(lang(it) + it.ck, Math.max(bestRes.get(lang(it) + it.ck) ?? 0, it.rh))
    const seen = new Map<string, number>()
    for (const it of items) {
      if (it.rh < 1080 && (bestRes.get(lang(it) + it.ck) ?? 0) >= 1080) continue
      // Arabic bundles the same AD/StarzPlay channel at many quality tiers, so
      // collapse it tier-agnostically there; elsewhere keep tiers distinct.
      const key = lang(it) + (isArabic ? dedupeKey(it.c.name) : dedupeKey(it.c.name, it.q))
      const n = seen.get(key) ?? 0
      if (n >= 2) continue // keep best feed + one spare
      seen.set(key, n + 1)
      channels.push(it.c)
    }
  }

  // Mint stable synthetic tvg-ids for channels the provider left untagged, so they
  // can still carry EPG in TiviMate (which maps EPG to channels by id only). Prefer
  // a twin's id — even a twin the dedupe dropped — so duplicate feeds share its guide
  // (and its EPG-learned competitions); else a per-stream id. Identical-
  // name myepg matches then attach to these; the country guard can't misfire on them
  // (synthetic ids have no country suffix).
  //
  // Key by BUCKET + prefix-stripped name. The bucket is the region ("DAZN 1" in
  // the Spanish bucket stays distinct from the UK one), while the stripped prefix
  // collapses a channel's parallel feed families (8K/NM/BE/UHD × RAW/HEVC/HD) onto
  // one id — so the untagged copies inherit whichever real broadcaster id the
  // provider put on one of them, and myepg's guide lights up all of them.
  const sibKey = (c: Channel) => `${c.group}|${epgExactNorm(c.name)}`
  const sibId = new Map<string, string>()
  for (const { c } of [...byBucket.values()].flat()) {
    if (!isRegular(c)) continue
    const k = sibKey(c)
    if (k && !sibId.has(k)) sibId.set(k, c.tvgId)
  }
  for (const c of channels) {
    if (c.isEventSlot || c.tvgId) continue
    const k = sibKey(c)
    c.tvgId = (k && sibId.get(k)) || `sx.${c.streamId}`
    if (k && !sibId.has(k)) sibId.set(k, c.tvgId)
  }

  // On-demand UFC PPV replays + the 4K movie library (appended after the live,
  // EPG-tagged channels; both are VOD with no tvg-id, so they get no EPG row).
  channels.push(...vodList)
  return channels
}

/**
 * Provider B's copy of each curated channel, listed right after provider A's feeds of it, so
 * TiviMate gets it as one more spare and the app as the family's last-ranked feed: failover reaches
 * it when A's feeds fail, and it is another account, so it plays while A says busy. B's channels are
 * matched to A's by bucket (from B's category name) + chanKey, best marker tier first, one per
 * channel; B's event slots (named for the event) and channels A lacks are left out. A's guide id
 * and logo carry over. Unchanged when B is not configured.
 */
export async function withBackupFeeds(channels: Channel[], b = backupConfigFromEnv()): Promise<Channel[]> {
  if (!b) return channels
  const [cats, streams] = await Promise.all([
    apiGet<Category[]>(b, 'get_live_categories'),
    apiGet<Stream[]>(b, 'get_live_streams'),
  ]).catch(() => [[], []] as [Category[], Stream[]]) // B down: serve A alone
  const catName = new Map(cats.map((c) => [c.category_id, c.category_name]))
  const familyKey = (group: string, name: string) => `${group}|${chanKey(name)}`
  const wanted = new Set(channels.filter((c) => !c.vodExt && !c.isEventSlot).map((c) => familyKey(c.group, c.name)))
  const best = new Map<string, { s: Stream; q: number }>()
  for (const s of streams) {
    const group = catName.get(String(s.category_id))
    const name = String(s.name ?? '').trim()
    if (!group || !name || EVENT_GROUP.test(group) || SEPARATOR.test(name)) continue
    const q = qualityScore(name, group)
    const key = familyKey(bucketForCategory(group), name)
    if (q === 0 || !wanted.has(key) || (best.get(key)?.q ?? -1) >= q) continue
    best.set(key, { s, q })
  }
  const lastOf = new Map<string, number>()
  // After the family's last A feed, event slots too (one can share a family with a 24/7 channel).
  channels.forEach((c, i) => { if (!c.vodExt) lastOf.set(familyKey(c.group, c.name), i) })
  const after = new Map<number, Channel>()
  for (const [key, { s, q }] of best) {
    const i = lastOf.get(key)!
    const a = channels[i]
    after.set(i, { streamId: BACKUP_ID + s.stream_id, name: String(s.name).trim(), logo: a.logo, tvgId: a.tvgId, group: a.group, isEventSlot: false, q, backup: true })
  }
  return channels.flatMap((c, i) => (after.has(i) ? [c, after.get(i)!] : [c]))
}

// Buckets whose channels may be listed under a competition (football only).
export const COMP_SOURCE = new Set<string>([B.uk, B.fbPpv, B.ar, B.de, B.es, B.it, B.fr, B.plAbroad])
// An EPG-learned association is trusted for this long (a cup round is ~monthly).
const LEARN_DAYS = 60
// A channel named for another sport (Sky Sports Golf/Cricket/Darts) shows football only
// when Sky overflows a match onto it, so it's listed only from the day the learner sees
// the match (dated by kick-off) until the day after.
const OVERFLOW_DAYS = 1

/**
 * One "🏆 …" group per competition, leading the playlist, then the curated channels.
 * Each group lists every football-bucket channel carrying that competition — by
 * name/category, or learned from the EPG for 24/7 channels (event slots count only
 * by their CURRENT name). Always-on channels come first (an idle PPV slot's "4K"
 * marker can't be verified, so it never outranks a channel that is actually on air),
 * then quality, then language (EN > AR > DE/ES); best feed plus one spare per
 * channel. Copies keep the original's tvg-id so the guide follows them; the EPG
 * endpoint never sees them.
 */
export function withCompetitionGroups(channels: Channel[], now = Date.now()): Channel[] {
  const learned = learnedFile.read()
  const since = new Date(now - LEARN_DAYS * 864e5).toISOString().slice(0, 10)
  const overflowSince = new Date(now - OVERFLOW_DAYS * 864e5).toISOString().slice(0, 10)
  type Row = { c: Channel; rh: number; rf: number; lang: number; region: number; idx: number }
  const byComp = new Map<string, Row[]>()
  channels.forEach((c, idx) => {
    // Provider-B feeds stay in their home group: the app reaches them through the family.
    if (c.vodExt || c.backup || !COMP_SOURCE.has(c.group)) return
    const keys = new Set(c.comps)
    // EPG-learned memberships apply to 24/7 channels only — never to event slots
    // (their name IS the event) nor to news/highlights channels whose guide merely
    // mentions a match ("Premier League Weekend Live" on Sky Sports News).
    if (!c.isEventSlot && !WOMEN.test(c.name) && !SHOW.test(c.name)) {
      const from = OTHER_SPORT.test(c.name) ? overflowSince : since
      for (const [k, seen] of Object.entries(learned[c.tvgId] ?? {})) if (seen >= from) keys.add(k)
    }
    if (!keys.size) return
    const { h: rh, fps: rf } = realRes(c.streamId, c.q ?? 2, c.isEventSlot || EVENT_ONLY.test(c.name), c.name)
    const row: Row = { c, rh, rf, lang: languageRank(c.name), region: regionPref(c.name), idx }
    for (const k of keys) (byComp.get(k) ?? byComp.set(k, []).get(k)!).push(row)
  })
  const groups: Channel[] = []
  for (const comp of COMPS) {
    const rows = byComp.get(comp.key)
    if (!rows) continue
    rows.sort(
      (a, b) =>
        Number(a.c.isEventSlot) - Number(b.c.isEventSlot) ||
        b.rh - a.rh || b.rf - a.rf || audio(b.c.streamId) - audio(a.c.streamId) || (b.c.q ?? 0) - (a.c.q ?? 0) ||
        a.lang - b.lang || a.region - b.region || a.idx - b.idx,
    )
    const seen = new Map<string, number>()
    for (const r of rows) {
      // An abroad channel is another broadcaster's even when named alike (NZ Sky Sport PL ≠ UK's).
      const k = (r.c.group === B.plAbroad ? 'abroad ' : '') + chanKey(r.c.name)
      const n = seen.get(k) ?? 0
      if (n >= 2) continue // best feed + one spare
      seen.set(k, n + 1)
      groups.push({ ...r.c, group: comp.group })
    }
  }
  return [...groups, ...channels.filter((c) => c.group !== B.plAbroad)]
}

const q = (v: string) => v.replace(/"/g, "'")

/** `backup` is provider B's front and account, for its feeds (withBackupFeeds). */
export function buildM3U(cfg: XtreamConfig, channels: Channel[], epgUrls: string[], backup?: XtreamConfig): string {
  const lines = [`#EXTM3U url-tvg="${epgUrls.join(',')}"`]
  for (const c of channels) {
    const src = c.vodExt
      ? streamUrl(cfg, 'movie', c.streamId, c.vodExt)
      : c.backup && backup
        ? streamUrl(backup, 'live', c.streamId - BACKUP_ID)
        : streamUrl(cfg, 'live', c.streamId)
    lines.push(
      `#EXTINF:-1 tvg-id="${q(c.tvgId)}" tvg-name="${q(c.name)}" tvg-logo="${q(c.logo)}" group-title="${q(c.group)}",${c.name}`,
      src,
    )
  }
  return lines.join('\n') + '\n'
}

export const unxml = (s: string) =>
  s.replace(/&(?:#(\d+)|#x([0-9a-f]+)|(amp|lt|gt|quot|apos));/gi, (_, dec, hex, name) =>
    dec ? String.fromCodePoint(Number(dec)) : hex ? String.fromCodePoint(parseInt(hex, 16))
      : ({ amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" } as Record<string, string>)[name.toLowerCase()])

const xml = (v: string) =>
  v.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

const xmltvTime = (d: Date) =>
  d.toISOString().replace(/[-:T]/g, '').replace(/\.\d+Z$/, '') + ' +0000'

// Name normalization for matching our channels against myepg display-names.
// Drops the provider's "REGION:" prefix, glues "ITV 1" -> "itv1"; fuzzy also
// strips plural 's' + reorders tokens so "UK: SKY SPORT F1" == "Sky Sports F1".
const EPG_QUAL = new Set(['8k', '4k', 'hd', 'sd', 'fhd', 'uhd', 'raw', 'vip', 'hevc', 'fps', 'mobile', 'mobil'])
const epgExactNorm = (s: string) =>
  s.replace(FEED_PREFIX, '').toLowerCase().replace(/⚽/g, 'o')
    .replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim().replace(/ (\d)/g, '$1')
// Fuzzy form from an already-computed exact norm — callers that need both pass
// `ex` in so epgExactNorm runs once, not twice.
const epgFuzzyTokens = (ex: string) =>
  ex.split(' ').filter((t) => t && !EPG_QUAL.has(t)).map((t) => t.replace(/s$/, '')).sort().join('')
// Full-name normalizer (keeps region/quality words): an identical full-name match
// is the same channel even if the country suffix differs (e.g. beIN FR uses .qa).
const epgRawNorm = (s: string) =>
  s.toLowerCase().replace(/⚽/g, 'o').replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim()
// Country suffix of an xmltv id, e.g. "dazn1.es" -> "es".
const epgCountry = (id: string) => id.match(/\.([a-z]{2,3})$/i)?.[1].toLowerCase() ?? ''
// A regular (non-event) channel that can carry real EPG — i.e. has a tvg-id.
const isRegular = (c: Channel) => !c.isEventSlot && !!c.tvgId
// Channel id of a <programme> block (what TiviMate maps EPG by).
const programmeChannel = (block: string) => block.match(/\bchannel="([^"]*)"/)?.[1] ?? ''

/** Real myepg programmes, re-tagged to OUR channel tvg-ids (TiviMate maps by id). */
export interface MyepgGuide {
  /** <programme> blocks with the channel attr rewritten to our tvg-id */
  programmes: string[]
  /** our tvg-ids that received myepg programmes, so provider EPG only fills the rest */
  covered: Set<string>
  /** our channel name or tvg-id -> the guide channel's <icon>: real-shape logos, where the
   *  provider's are 96×96 squares with wide logos squashed in */
  icons: Map<string, string>
}

/** Our channels keyed by every way a myepg <channel> might name them. */
interface MyepgLookups {
  ids: Map<string, string> // lower-cased tvg-id -> tvg-id (exact id match)
  byRaw: Map<string, string> // identical full name -> tvg-id
  byExact: Map<string, string> // prefix-stripped name -> tvg-id
  byFuzzy: Map<string, string> // token-sorted name -> tvg-id
}

// Resolve one myepg <channel> block to OUR tvg-ids, in priority order: exact id, identical
// full name, then same-country looser name (first match wins) — plus our channel whose id
// differs only in case, and the channels Jev matched to it offline (`matches`), which none
// of the name rules reach.
function resolveMyepgChannel(
  block: string,
  remap: Map<string, string[]>,
  claimed: Set<string>,
  covered: Set<string>,
  lookups: MyepgLookups,
  matches: Record<string, string[]>,
  icons: Map<string, string>,
): void {
  const idm = block.match(/\bid="([^"]*)"/)
  if (!idm) return
  const mid = idm[1]
  if (remap.has(mid)) return
  const sameId = lookups.ids.get(mid.toLowerCase()) // guides vary the case: beinsports3.fr
  let our = sameId === mid ? mid : undefined // exact id — always the same channel
  if (!our) {
    for (const dm of block.matchAll(/<display-name[^>]*>([^<]*)<\/display-name>/g)) {
      const n = dm[1]
      if (/\d{4}-\d{2}-\d{2}|\bvs\b| \| /i.test(n)) continue // skip event-style names
      // identical FULL name = same channel (country suffix may legitimately differ)
      our = lookups.byRaw.get(epgRawNorm(n))
      if (our) break
      // looser name match ONLY within the same country, else "DAZN 1" (ES) wrongly
      // grabs "DAZN 1" (UK), "Sky Sport" (DE) grabs (NZ)… — wrong EPG is worse than none.
      const ex = epgExactNorm(n)
      const cand = lookups.byExact.get(ex) ?? lookups.byFuzzy.get(epgFuzzyTokens(ex))
      if (cand && epgCountry(mid) && epgCountry(mid) === epgCountry(cand)) { our = cand; break }
    }
  }
  // A channel already claimed in this guide is skipped — except for Jev's matches: guides often
  // list a channel twice, once with no programmes, and that empty entry may have claimed it.
  const ours = [...new Set([
    ...[our, sameId].filter((id): id is string => !!id && !claimed.has(id)),
    ...(matches[mid] ?? []).filter((id) => lookups.ids.get(id.toLowerCase()) === id),
  ])].filter((id) => !covered.has(id))
  if (!ours.length) return
  remap.set(mid, ours)
  const icon = guideIcon(block)
  for (const id of ours) {
    claimed.add(id)
    if (icon && !icons.has(id)) icons.set(id, icon)
  }
}

/** A guide <channel>'s logo, unless it is myepg's placeholder. */
function guideIcon(block: string): string | undefined {
  const src = block.match(/<icon\b[^>]*\bsrc="([^"]+)"/)?.[1]
  return src && !src.endsWith('/myepg-logo.png') ? unxml(src) : undefined
}

// The guide lists many of our feeds under their exact provider name, often under the right channel
// where the provider's tvg-id points elsewhere (its Sky Sports F1 UHD carries the darts id), so
// those logos go by name, ahead of the tvg-id's.
function collectNameIcons(block: string, names: Set<string>, icons: Map<string, string>): void {
  const icon = guideIcon(block)
  if (!icon) return
  for (const dm of block.matchAll(/<display-name[^>]*>([^<]*)<\/display-name>/g)) {
    const n = unxml(dm[1]).trim()
    if (names.has(n) && !icons.has(n)) icons.set(n, icon)
  }
}

/**
 * Stream each myepg guide, match its channels to OURS (by id, then exact, then
 * fuzzy display-name), and collect that channel's <programme> blocks re-tagged
 * with our tvg-id — so TiviMate, which maps EPG to channels by id only, shows
 * real schedules. XMLTV declares every <channel> before any <programme>, so a
 * single streaming pass builds the id remap, then re-tags programmes. Scoped to
 * our channels, so memory stays modest despite the ~170MB/guide payload.
 * Best-effort: a failed source just yields fewer programmes.
 */
export async function fetchMyepgGuide(
  urls: string[],
  channels: Channel[],
  matches: Record<string, string[]> = epgMatchesFile.read(),
): Promise<MyepgGuide> {
  const programmes: string[] = []
  const covered = new Set<string>()
  const icons = new Map<string, string>()
  const names = new Set(channels.filter(isRegular).map((c) => c.name))
  // OUR channels, keyed by every way a myepg <channel> might name them.
  const lookups: MyepgLookups = { ids: new Map(), byRaw: new Map(), byExact: new Map(), byFuzzy: new Map() }
  const put = (m: Map<string, string>, key: string, id: string) => { if (key && !m.has(key)) m.set(key, id) }
  for (const c of channels) {
    if (!isRegular(c)) continue
    const ex = epgExactNorm(c.name)
    put(lookups.ids, c.tvgId.toLowerCase(), c.tvgId)
    put(lookups.byRaw, epgRawNorm(c.name), c.tvgId)
    put(lookups.byExact, ex, c.tvgId)
    put(lookups.byFuzzy, epgFuzzyTokens(ex), c.tvgId)
  }
  if (!lookups.ids.size) return { programmes, covered, icons }

  for (const url of urls) {
    try {
      const resp = await fetch(url, { headers: { 'User-Agent': 'VLC/3.0.18' } })
      if (!resp.ok || !resp.body) continue
      const reader = resp.body.pipeThrough(new DecompressionStream('gzip')).getReader()
      const decoder = new TextDecoder()
      const remap = new Map<string, string[]>() // myepg channel id -> our tvg-ids
      // Ours claimed by a channel of this guide. Only channels an earlier guide actually fed
      // (`covered`) are closed to it, so an entry without programmes blocks no other guide.
      const claimed = new Set<string>()
      const feeder = new Map<string, string>() // our tvg-id -> the one guide channel feeding it
      let buf = ''
      let inProgrammes = false
      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })
        // Walk complete elements with a cursor, slicing the buffer ONCE per chunk —
        // slicing per element re-copies the tail (O(n²) over a ~170MB guide).
        if (!inProgrammes) {
          let pos = 0
          let i: number
          while ((i = buf.indexOf('</channel>', pos)) >= 0) {
            const s = buf.lastIndexOf('<channel', i)
            if (s >= 0) {
              const block = buf.slice(s, i)
              resolveMyepgChannel(block, remap, claimed, covered, lookups, matches, icons)
              collectNameIcons(block, names, icons)
            }
            pos = i + 10
          }
          buf = buf.slice(pos)
          const p = buf.indexOf('<programme')
          if (p >= 0) { inProgrammes = true; buf = buf.slice(p) }
        }
        if (inProgrammes) {
          let pos = 0
          let i: number
          while ((i = buf.indexOf('</programme>', pos)) >= 0) {
            const s = buf.lastIndexOf('<programme', i)
            if (s >= 0) {
              const block = buf.slice(s, i + 12)
              const cs = block.indexOf('channel="')
              if (cs >= 0) {
                const ce = block.indexOf('"', cs + 9)
                const src = block.slice(cs + 9, ce)
                for (const our of remap.get(src) ?? []) {
                  // a Jev match and a name match can both point at ours — never feed it twice
                  if ((feeder.get(our) ?? src) !== src) continue
                  feeder.set(our, src)
                  programmes.push(block.slice(0, cs) + `channel="${xml(our)}"` + block.slice(ce + 1))
                  covered.add(our)
                }
              }
            }
            pos = i + 12
          }
          buf = buf.slice(pos)
        }
      }
      try { await reader.cancel() } catch { /* already drained */ }
    } catch {
      /* skip this source */
    }
  }
  // An id the provider also stamps on a differently-named channel (its Arabic kids channels share
  // one, SKYGO Sky Sport F1 carries the darts id) would give them all one wrong logo: none goes by it.
  const keys = new Map<string, Set<string>>()
  for (const c of channels) if (isRegular(c)) (keys.get(c.tvgId) ?? keys.set(c.tvgId, new Set()).get(c.tvgId)!).add(chanKey(c.name))
  for (const [id, k] of keys) if (k.size > 1) icons.delete(id)
  return { programmes, covered, icons }
}

/**
 * XMLTV for the curated playlist, self-contained for TiviMate (which maps EPG to
 * channels by tvg-id only). EVERY curated channel gets a guide entry:
 *  - event/PPV slots: a synthetic programme (channel name IS the title — the IPTV
 *    Editor "use channel name as EPG" trick), 1h blocks from -1h to +24h.
 *  - regular channels myepg covers: its real programmes (re-tagged to our ids).
 *  - else the provider's xmltv (exact-id match), scoped to our channels.
 *  - anything still uncovered falls back to the same name-as-title, so the guide
 *    never has a blank row.
 */
export function buildEventEpg(
  channels: Channel[],
  providerXml = '',
  myepg?: MyepgGuide,
): string {
  const slots = channels.filter((c) => c.isEventSlot)
  const covered = myepg?.covered ?? new Set<string>()
  // unique regular channels, one per tvg-id (HD/UHD feeds may share an id)
  const regular: Channel[] = []
  const seenReg = new Set<string>()
  for (const c of channels) {
    if (c.isEventSlot || !c.tvgId || seenReg.has(c.tvgId)) continue
    seenReg.add(c.tvgId)
    regular.push(c)
  }
  // provider fills ONLY channels myepg lacks
  const wantIds = new Set(regular.filter((c) => !covered.has(c.tvgId)).map((c) => c.tvgId))

  const out: string[] = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="iptv-preview">']
  // hourly start/stop xmltv stamps for the -1h..+24h window, computed once and
  // shared by every name-as-title channel (was recomputed per channel).
  const base = new Date()
  base.setUTCMinutes(0, 0, 0)
  base.setUTCHours(base.getUTCHours() - 1)
  const hourStamps = Array.from({ length: 25 }, (_, h) => {
    const a = new Date(base.getTime() + h * 3600_000)
    return ` start="${xmltvTime(a)}" stop="${xmltvTime(new Date(a.getTime() + 3600_000))}"`
  })
  const nameAsTitle = (c: Channel) => {
    const tail = ` channel="${xml(c.tvgId)}"><title>${xml(c.name)}</title><desc>${xml(c.group)}</desc></programme>`
    for (const stamp of hourStamps) out.push(`<programme${stamp}${tail}`)
  }

  // --- channels: declare every curated channel (PPV slots + all regular) ---
  for (const c of [...slots, ...regular]) {
    out.push(`<channel id="${xml(c.tvgId)}"><display-name>${xml(c.name)}</display-name></channel>`)
  }

  // --- programmes ---
  for (const c of slots) nameAsTitle(c)
  const gotProg = new Set<string>() // channels that actually received real programmes
  const realByCh = new Map<string, string[]>() // their programme blocks, for sibling feeds to borrow
  const real = (id: string, block: string) => {
    out.push(block)
    gotProg.add(id)
    ;(realByCh.get(id) ?? realByCh.set(id, []).get(id)!).push(block)
  }
  // Qatari guides (beIN, Al Kass, …) run on two clocks: the provider's lowercase .qa ids
  // (beinsports1.qa) are right, its mixed-case twins (beINSports1En.qa) run 3h late, and
  // myepg mirrors either one. A channel whose programmes sit on the late clock more than
  // the right one showed the match before, so it is moved 3h earlier (+0200 -> +0500).
  const lateKeys = new Set<string>()
  const okKeys = new Set<string>()
  const at = (block: string, attr: 'start' | 'stop') => {
    const s = block.match(new RegExp(`\\b${attr}="(\\d{4})(\\d\\d)(\\d\\d)(\\d\\d)(\\d\\d)(\\d\\d) ([+-]\\d\\d)(\\d\\d)"`))
    return s ? Date.parse(`${s[1]}-${s[2]}-${s[3]}T${s[4]}:${s[5]}:${s[6]}${s[7]}:${s[8]}`) : NaN
  }
  const progKey = (block: string) => {
    const start = at(block, 'start')
    const title = block.match(/<title[^>]*>([^<]*)/)?.[1]
    return !Number.isNaN(start) && title ? `${title.replace(/\s+/g, ' ').trim()}|${start}` : ''
  }
  const lessThree = (block: string) =>
    block.replace(/\b(start|stop)="(\d{14}) ([+-])(\d\d)(\d\d)"/g, (_, k, ts, sign, hh, mm) => {
      const off = (sign === '-' ? -1 : 1) * (Number(hh) * 60 + Number(mm)) + 180
      const a = Math.abs(off)
      return `${k}="${ts} ${off < 0 ? '-' : '+'}${String(Math.floor(a / 60)).padStart(2, '0')}${String(a % 60).padStart(2, '0')}"`
    })
  // myepg real programmes (channel attr already rewritten to our tvg-ids)
  const blocks: [string, string][] = (myepg?.programmes ?? []).map((p) => [programmeChannel(p), p])
  // provider programmes for channels myepg lacks, and for the ones whose myepg schedule runs out
  // first (its beIN 1 stopped one match short of the provider's)
  const myepgEnd = new Map<string, number>()
  for (const [id, p] of blocks) myepgEnd.set(id, Math.max(myepgEnd.get(id) ?? 0, at(p, 'stop') || 0))
  const seenProg = new Set<string>()
  if (providerXml) {
    for (const m of providerXml.matchAll(/<programme\b[^>]*\bchannel="([^"]*)"[^>]*>[\s\S]*?<\/programme>/g)) {
      if (m[1].endsWith('.qa')) (/[A-Z]/.test(m[1]) ? lateKeys : okKeys).add(progKey(m[0]))
      if (!wantIds.has(m[1]) && !(at(m[0], 'start') >= (myepgEnd.get(m[1]) ?? Infinity))) continue
      const startM = m[0].match(/\bstart="([^"]*)"/)
      const k = m[1] + '|' + (startM ? startM[1] : out.length)
      if (seenProg.has(k)) continue // skip duplicate programme declarations
      seenProg.add(k)
      blocks.push([m[1], m[0]])
    }
  }
  const lateness = new Map<string, number>() // our tvg-id -> late-clock hits minus right-clock hits
  for (const [id, p] of blocks) {
    const k = progKey(p)
    const d = lateKeys.has(k) ? 1 : okKeys.has(k) ? -1 : 0
    if (d) lateness.set(id, (lateness.get(id) ?? 0) + d)
  }
  for (const [id, p] of blocks) real(id, (lateness.get(id) ?? 0) > 0 ? lessThree(p) : p)
  // Parallel feeds of one channel can carry DIFFERENT provider tvg-ids — TNT Sports 1
  // is "TNTSport1.uk" on the UK/VIP feeds but "tntsports1.uk" on NOW, and only the
  // latter exists in the guides — so an id with no programmes borrows its name-
  // sibling's, re-tagged. A donor must be unambiguous: an id the provider also
  // stamps on a differently-named channel (SkySportsNews.uk on a Main Event feed)
  // never donates, or it would spread the wrong guide.
  const sibIds = new Map<string, Set<string>>() // bucket|chanKey -> tvg-ids
  const idKeys = new Map<string, Set<string>>() // tvg-id -> bucket|chanKeys it appears under
  for (const c of channels) {
    if (c.isEventSlot || !c.tvgId) continue
    const k = `${c.group}|${chanKey(c.name)}`
    ;(sibIds.get(k) ?? sibIds.set(k, new Set()).get(k)!).add(c.tvgId)
    ;(idKeys.get(c.tvgId) ?? idKeys.set(c.tvgId, new Set()).get(c.tvgId)!).add(k)
  }
  for (const ids of sibIds.values()) {
    if (ids.size < 2) continue
    const donor = [...ids].find((id) => realByCh.has(id) && idKeys.get(id)!.size === 1)
    if (!donor) continue
    for (const id of ids) {
      if (gotProg.has(id)) continue
      for (const p of realByCh.get(donor)!) out.push(p.replace(/\bchannel="[^"]*"/, `channel="${xml(id)}"`))
      gotProg.add(id)
    }
  }
  // fallback: any regular channel that received no real programmes gets
  // name-as-title, so no row is ever blank in TiviMate
  for (const c of regular) {
    if (!gotProg.has(c.tvgId)) nameAsTitle(c)
  }

  out.push('</tv>')
  return out.join('\n')
}

/** Fetch the provider's full XMLTV guide; '' on any failure (best-effort). */
export async function fetchProviderEpg(cfg: XtreamConfig): Promise<string> {
  try {
    const resp = await fetch(guideUrl(cfg), { headers: { 'User-Agent': 'VLC/3.0.18' } })
    if (!resp.ok) return ''
    return await resp.text()
  } catch {
    return ''
  }
}

// The guide downloads (~47MB of myepg + the ~70MB provider guide) change slowly, and a
// burst of them gets the myepg account blocked, so they are reused for 6h. The guide built
// from them is NOT: a PPV slot's guide entry is its channel name, which changes from match to
// match, so a guide built hours ago shows the previous match (and its 24h window runs out).
const GUIDE_TTL = 6 * 3600_000
let guides: { at: number; job: Promise<[string, MyepgGuide]> } | undefined

/** The curated channels and the XMLTV guide built for them (served as /api/epg, parsed into the app's guide). */
export async function curatedGuide(): Promise<{ channels: Channel[]; xml: string }> {
  const cfg = configFromEnv()
  const epgUrls = (process.env.EPG_URLS ?? '').split(',').map((s) => s.trim()).filter(Boolean)
  const channelsPromise = fetchCuratedChannels(cfg, false) // VOD entries carry no guide
  if (!guides || Date.now() - guides.at > GUIDE_TTL) {
    // provider EPG doesn't need the channel list, so its download overlaps curation;
    // myepg needs the channels, so it waits for them
    const providerEpgPromise = fetchProviderEpg(cfg)
    const job = channelsPromise.then((channels) =>
      Promise.all([providerEpgPromise, fetchMyepgGuide(epgUrls, channels).then(async (g) => (await saveEpgIcons(g.icons), g))]))
    guides = { at: Date.now(), job }
    job.catch(() => { if (guides?.job === job) guides = undefined })
  }
  const [channels, [providerXml, myepg]] = await Promise.all([channelsPromise, guides.job])
  return { channels, xml: buildEventEpg(channels, providerXml, myepg) }
}
