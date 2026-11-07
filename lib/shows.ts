// The Fire TV app's shows (series). Every show in a category of the user's languages (English,
// Arabic, German, Spanish, Italian), once per language: a show filed in several of a language's
// categories keeps the 4K one's copy. A category's 4K and plain versions are one group. Built from
// the provider's daily series snapshot (seriesCatalogue in provider.ts); a show's episodes are
// fetched when it's opened.
import { readFile } from 'node:fs/promises'
import { writeAtomic } from './fs.js'
import { apiGet, claims4K, plainCategory, readName, seriesCatalogue, type SeriesCatalogue, type SeriesEntry, type XtreamConfig } from './provider.js'
import { categoryLabels, claimedPicture, measuredStream, pictureTier, probedPicture, streamLabels, type Measured, type ProbedStream } from './quality-labels.js'
import { episodeTitle, filmTitle, showTitle, tmdbTitle } from './tmdb.js'
import { filmEntries, filmTitleOf, vodCategoryName } from './vod.js'

/** The same shape as /api/app/movies, so the app reads both with one parser. */
export interface ShowList {
  groups: string[]
  /** [titleId] is the show's title id (see tmdb.ts). */
  movies: { id: number; titleId: string; name: string; poster: string; group: string }[]
}

export interface Episode {
  id: number
  season: number
  episode: number
  title: string
  ext: string
  /** Running time in seconds; 0 when the provider doesn't say. */
  secs: number
  /** A still from the episode; empty when the provider has none. */
  image: string
  /** Ready-to-show quality labels from the provider's probe ("4K", "HEVC", "31 Mbps", "E-AC3 5.1"), best first. */
  quality: string[]
}

const SKIP = /PODCAST|بودكاست|\(TR\)/i
const ENGLISH = /^(NETFLIX|DISNEY\+|APPLE\+|AMAZON|HBO MAX|OSN\+|PARAMOUNT\+|PEACOCK|SHOWTIME|SKY$|MARVEL|ENGLISH|NICKELODEON|DISCOVERY\+|FIFA\+|CRUNCHYROLL)/
/** The big services' main rows lead each language; the rest follow in the provider's order. */
const FIRST = ['NETFLIX SERIES', 'HBO MAX SERIES', 'APPLE+ SERIES', 'DISNEY+ SERIES', 'AMAZON SERIES', 'PARAMOUNT+', 'ENGLISH SERIES']
/** Language order: English, Arabic, then German, Spanish, Italian. */
const LANGS = ['en', 'ar', 'de', 'es', 'it'] as const

export function language(cat: string): (typeof LANGS)[number] | null {
  if (SKIP.test(cat)) return null
  if (cat.startsWith('GERMANY')) return 'de'
  if (cat.startsWith('ESPAÑA')) return 'es'
  if (cat.startsWith('ITALY')) return 'it'
  if (ENGLISH.test(cat)) return 'en'
  // Arabic-script names are Arabic categories, except the Persian, Kurdish and Hebrew ones (prefixed).
  if (/[؀-ۿ]/.test(cat) && !/^(PERSIAN|KURDISH|HEBREW)/.test(cat)) return 'ar'
  return null
}

export function buildShowList({ cats, series }: SeriesCatalogue): ShowList {
  const catOrder = new Map(cats.map((c, i) => [c.id, i]))
  const catName = new Map(cats.map((c) => [c.id, c.name]))
  type Pick = { s: SeriesEntry; cat: string; lang: number; k4: boolean }
  const rank = (p: Pick) => {
    const i = FIRST.indexOf(plainCategory(p.cat))
    return i < 0 ? FIRST.length : i
  }
  const best = new Map<string, Pick>()
  for (const s of series) {
    const cat = catName.get(s.cat)
    const lang = cat ? language(cat) : null
    if (!cat || !lang) continue
    const key = `${lang}|${s.tmdb || s.name.toLowerCase()}`
    const k4 = claims4K(cat)
    if (!best.has(key) || (k4 && !best.get(key)!.k4)) best.set(key, { s, cat, lang: LANGS.indexOf(lang), k4 })
  }
  const picks = [...best.values()].sort(
    (a, b) => a.lang - b.lang || rank(a) - rank(b) || catOrder.get(a.s.cat)! - catOrder.get(b.s.cat)!,
  )
  const movies = picks.map(({ s, cat }) => ({ id: s.id, titleId: showTitle(s.tmdb, s.id), name: readName(s.name).title, poster: s.cover, group: plainCategory(cat) }))
  return { groups: [...new Set(movies.map((m) => m.group))], movies }
}

const built = new WeakMap<SeriesCatalogue, ShowList>()

/** The show list, from the daily series snapshot. */
export async function showList(cfg: XtreamConfig): Promise<ShowList> {
  const c = await seriesCatalogue(cfg)
  let list = built.get(c)
  if (!list) built.set(c, (list = buildShowList(c)))
  return list
}

interface SeriesInfo {
  info?: { plot?: string; genre?: string; cast?: string; rating?: string | number; backdrop_path?: string[] | string }
  episodes?: Record<string, { id: string; episode_num: number | string; title?: string; container_extension?: string; season?: number; info?: { duration_secs?: number | string; movie_image?: string } & ProbedStream }[]>
}

/** Each series' info, kept in-process: episode copies ask once per language listing, so every episode after a show's first answers at once. */
const seriesInfos = new Map<number, { at: number; info: Promise<SeriesInfo> }>()
const SERIES_KEEP_MS = 6 * 3600_000

/** The provider's `get_series_info`, shared by showEpisodes and episodeEntries; a failure is not kept. */
function seriesInfo(cfg: XtreamConfig, id: number): Promise<SeriesInfo> {
  const now = Date.now()
  const hit = seriesInfos.get(id)
  if (hit && now - hit.at < SERIES_KEEP_MS) return hit.info
  for (const [k, v] of seriesInfos) if (now - v.at >= SERIES_KEEP_MS) seriesInfos.delete(k)
  const info = apiGet<SeriesInfo>(cfg, `get_series_info&series_id=${id}`)
  seriesInfos.set(id, { at: now, info })
  info.catch(() => seriesInfos.get(id)?.info === info && seriesInfos.delete(id))
  return info
}

/** The first of the provider's backdrops (a list, sometimes one string); "" when none. */
const firstBackdrop = (b: string[] | string | undefined) => String((Array.isArray(b) ? b[0] : b) ?? '').trim()
/** The first few names of a cast list. */
const castOf = (c: string | undefined) => String(c ?? '').split(',').map((s) => s.trim()).filter(Boolean).slice(0, 4).join(', ')
const ratingOf = (r: string | number | undefined) => (Number(r) > 0 ? Number(r).toFixed(1) : '')

/** A show's title id by series id, from the show list (`iptv:tv:<id>` for one it lacks). */
async function showTitleOf(cfg: XtreamConfig): Promise<(id: number) => string> {
  const byId = new Map((await showList(cfg).catch(() => null))?.movies.map((m) => [m.id, m.titleId]))
  return (id) => byId.get(id) ?? showTitle('', id)
}

/** A show's title id, plot, backdrop, genres, cast and rating, and its episodes (each with its title id) in season and episode order. */
export async function showEpisodes(cfg: XtreamConfig, seriesId: number, show?: string) {
  const info = await seriesInfo(cfg, seriesId)
  const titleId = show ?? (await showTitleOf(cfg))(seriesId)
  const episodes = Object.entries(info.episodes ?? {})
    .flatMap(([season, eps]) =>
      eps.map((e) => ({
        id: Number(e.id),
        titleId: episodeTitle(titleId, Number(e.season ?? season), Number(e.episode_num)),
        season: Number(e.season ?? season),
        episode: Number(e.episode_num),
        // "A+ - Severance (2022) (US) - S01E01 - Good News About Hell" → "Good News About Hell"; empty when untitled.
        title: String(e.title ?? '').match(/S\d+\s*E\d+\s*-\s*(.+)$/i)?.[1]?.trim() ?? '',
        ext: e.container_extension || 'mkv',
        secs: Number(e.info?.duration_secs) || 0,
        image: String(e.info?.movie_image ?? '').trim(),
        quality: streamLabels(e.info),
        tier: pictureTier(probedPicture(e.info) ?? { width: 0, height: 0 }) ?? '',
      })),
    )
    .filter((e) => e.id > 0)
    .sort((a, b) => a.season - b.season || a.episode - b.episode)
  const i = info.info ?? {}
  return { titleId, plot: String(i.plot ?? '').trim(), backdrop: firstBackdrop(i.backdrop_path), genre: String(i.genre ?? '').trim(), cast: castOf(i.cast), rating: ratingOf(i.rating), episodes }
}

/**
 * Every copy of an episode the provider has, one per language the show is listed in: stream id,
 * container, its probe, its category and the language that category is in.
 */
export async function episodeEntries(cfg: XtreamConfig, show: string, season: number, episode: number) {
  const lists = (await showList(cfg)).movies.filter((m) => m.titleId === show)
  const found = await Promise.all(lists.map(async (m) => {
    const info = await seriesInfo(cfg, m.id).catch(() => null)
    const e = Object.entries(info?.episodes ?? {}).flatMap(([s, eps]) => eps.filter((e) => Number(e.season ?? s) === season && Number(e.episode_num) === episode))[0]
    return e && Number(e.id) > 0 ? { id: Number(e.id), ext: e.container_extension || 'mkv', probe: e.info, category: m.group, lang: language(m.group) ?? '' } : null
  }))
  return found.filter((e) => e !== null)
}

interface VodInfo {
  info?: { plot?: string; description?: string; genre?: string; duration_secs?: number | string; rating?: string | number; backdrop_path?: string[] | string; cast?: string; actors?: string; director?: string } & ProbedStream
  movie_data?: { category_id?: string | number }
}

/**
 * Title ids for provider ids the app saved before it keyed marks by title (POST /api/app/title-ids):
 * every film and show maps (an `iptv:` id when it has no TMDB id); an episode maps only when its show's
 * episode list could be read, so the app retries the rest later. An unreadable catalogue fails the call
 * (the app retries) rather than mapping films to `iptv:` ids the app would keep for good.
 */
export async function titleIds(cfg: XtreamConfig, req: { movies?: number[]; shows?: number[]; episodes?: { series: number; id: number }[] }) {
  const [film, show] = await Promise.all([filmTitleOf(cfg), showTitleOf(cfg)])
  const episodes: Record<string, string> = {}
  for (const series of new Set(req.episodes?.map((e) => e.series))) {
    const wanted = new Set(req.episodes!.filter((e) => e.series === series).map((e) => e.id))
    const eps = (await showEpisodes(cfg, series, show(series)).catch(() => null))?.episodes ?? []
    for (const e of eps) if (wanted.has(e.id)) episodes[e.id] = e.titleId
  }
  return {
    movies: Object.fromEntries((req.movies ?? []).map((id) => [id, film(id)])),
    shows: Object.fromEntries((req.shows ?? []).map((id) => [id, show(id)])),
    episodes,
  }
}

/** A film's plot, running time (seconds, 0 when unknown), genres, rating, backdrop, cast, director ("" when unknown) and quality labels. */
export async function movieInfo(cfg: XtreamConfig, vodId: number) {
  const res = await apiGet<VodInfo>(cfg, `get_vod_info&vod_id=${vodId}`)
  const info = res.info ?? {}
  // Best first: what the app measured when the film played; the provider's own probe (rare); else what
  // its category claims plus its bitrate, an estimate.
  const measured = (await measuredFilms())[String(vodId)]
  const stream = measured ? measuredStream(measured, Number(info.bitrate) > 0 ? info.bitrate : measured.kbps) : info.video && Object.keys(info.video).length ? info : null
  const category = stream ? '' : await vodCategoryName(cfg, Number(res.movie_data?.category_id)).catch(() => '')
  const quality = stream ? streamLabels(stream) : categoryLabels(category, info.bitrate)
  const tier = pictureTier((stream ? probedPicture(stream) : claimedPicture(category, info.bitrate)) ?? { width: 0, height: 0 }) ?? ''
  const titleId = (await filmTitleOf(cfg).catch(() => null))?.(vodId) ?? filmTitle('', vodId)
  return {
    titleId,
    quality,
    tier,
    /** The tier comes from the category's claim, not the stream: the app measures it on first play. */
    estimated: !stream && tier !== '',
    secs: Number(info.duration_secs) || 0,
    rating: ratingOf(info.rating),
    backdrop: firstBackdrop(info.backdrop_path),
    ...(await filmText(cfg, vodId, titleId, info)),
  }
}

const textOf = (info: NonNullable<VodInfo['info']>) => ({
  plot: String(info.plot || info.description || '').trim(),
  genre: String(info.genre ?? '').trim(),
  cast: castOf(info.cast || info.actors),
  director: String(info.director ?? '').trim(),
})

/**
 * A film's plot, genres, cast and director in English: TMDB's when it knows the film (the provider's
 * director is often a studio, its plot in the listing's language), else an English listing's, else this one's.
 */
async function filmText(cfg: XtreamConfig, vodId: number, titleId: string, info: NonNullable<VodInfo['info']>) {
  const own = textOf(info)
  const tmdb = titleId.match(/^movie:(\d+)$/)?.[1]
  const t = tmdb ? await tmdbTitle('movie', tmdb).catch(() => null) : null
  if (t) {
    return {
      plot: t.plot || own.plot,
      genre: t.genres.slice(0, 2).join(', ') || own.genre,
      cast: t.cast.slice(0, 4).map((c) => c.name).join(', ') || own.cast,
      director: t.directors.map((d) => d.name).join(', ') || own.director,
    }
  }
  const entries = await filmEntries(cfg, titleId).catch(() => [])
  const english = (e: { category: string }) => readName('', e.category).lang === 'EN'
  const en = !entries.some((e) => e.id === vodId && english(e)) && entries.find(english)
  const other = en ? (await apiGet<VodInfo>(cfg, `get_vod_info&vod_id=${en.id}`).catch(() => null))?.info : null
  return other ? textOf(other) : own
}

// Films the app measured while they played (resolution, codec, HDR, frame rate, audio), by VOD id:
// a film's quality is then known rather than claimed, for every viewer. Kept on the box.
const MEASURED = new URL('../.cache/vod-measured.json', import.meta.url)
let measured: Promise<Record<string, Measured>> | null = null
export function measuredFilms(): Promise<Record<string, Measured>> {
  measured ??= readFile(MEASURED, 'utf8').then((t) => JSON.parse(t) as Record<string, Measured>).catch(() => ({}))
  return measured
}

/** Keeps the app's reading of film [id]; true when it changed what was known. */
export async function saveMeasuredFilm(id: number, m: Measured): Promise<boolean> {
  const all = await measuredFilms()
  if (JSON.stringify(all[String(id)]) === JSON.stringify(m)) return false
  all[String(id)] = m
  await writeAtomic(MEASURED, all)
  return true
}
