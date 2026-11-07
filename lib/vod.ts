// The app's view of the provider's whole VOD catalogue (the daily snapshot; see vodCatalogue in
// provider.ts): the Blu-ray row beside the 4K groups, and search over every film, so a film
// with no 4K copy ("Eternal Sunshine of the Spotless Mind") can still be found and played.
import { movieGroup } from './curation.js'
import { claims4K, plainCategory, readName, vodCatalogue, type VodCatalogue, type XtreamConfig } from './provider.js'
import { filmTitle } from './tmdb.js'

/** An item in /api/app/movies' shape. */
export interface VodItem {
  id: number
  /** The film's title id (see tmdb.ts). */
  titleId: string
  name: string
  poster: string
  group: string
  ext: string
}

/** The provider's 1080p Blu-ray films (multi-subs): one row after the 4K groups. */
const BLURAY = /^TOP MOVIES BLURAY/i
export const BLURAY_GROUP = '🎬 BLU-RAY MOVIES'

/** A title as the app shows it: the provider's tags off, its language tag kept as a prefix ("EN - "), which the app makes a badge. */
function cleanName(raw: string): string {
  const { title, lang } = readName(raw)
  return (lang ? `${lang} - ` : '') + title
}

const tmdbIndex = new WeakMap<VodCatalogue, Map<number, string>>()

/** A film's title id by VOD id, from the catalogue's tmdb column (`iptv:<id>` for a film it lacks). */
export async function filmTitleOf(cfg: XtreamConfig): Promise<(id: number) => string> {
  const c = await vodCatalogue(cfg)
  let tmdb = tmdbIndex.get(c)
  if (!tmdb) tmdbIndex.set(c, (tmdb = new Map(c.films.map((f) => [f.id, f.tmdb]))))
  return (id) => filmTitle(tmdb.get(id), id)
}

/** Every catalogue copy of a film: each entry with its TMDB id (`movie:<tmdb>`), or the one entry of an `iptv:<id>` title. Raw names keep the provider's tags. */
export async function filmEntries(cfg: XtreamConfig, title: string): Promise<{ id: number; name: string; category: string; ext: string }[]> {
  const tmdb = title.match(/^movie:(\d+)$/)?.[1]
  const vodId = Number(title.match(/^iptv:(\d+)$/)?.[1])
  if (!tmdb && !vodId) return []
  const { cats, films } = await vodCatalogue(cfg)
  return films.filter((f) => (tmdb ? f.tmdb === tmdb : f.id === vodId)).map(({ id, name, cat, ext }) => ({ id, name, category: cats[cat] ?? '', ext }))
}

/** A VOD category's name, "" when unknown. */
export async function vodCategoryName(cfg: XtreamConfig, id: number): Promise<string> {
  return (await vodCatalogue(cfg)).cats[id] ?? ''
}

/** Every film in the Blu-ray category, newest upload first. */
export async function blurayMovies(cfg: XtreamConfig): Promise<VodItem[]> {
  const { cats, films } = await vodCatalogue(cfg)
  return films
    .filter((f) => BLURAY.test(cats[f.cat] ?? ''))
    .sort((a, b) => b.added - a.added)
    .map((f) => ({ id: f.id, titleId: filmTitle(f.tmdb, f.id), name: cleanName(f.name), poster: f.icon, group: BLURAY_GROUP, ext: f.ext }))
}

/** Accents stripped, lowercased, punctuation to spaces. */
const norm = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim()
const YEAR = /\((\d{4})\)/

/** Which copy of a film to offer: 4K, then Blu-ray, then English, then the rest. */
function tier(cat: string, name: string): number {
  if (claims4K(cat)) return 0
  if (BLURAY.test(cat)) return 1
  if (readName(name, cat).lang === 'EN') return 2
  return 3
}

/** A copy filed under another language ("FR - FILM 4K", "4K-FR - …"): offered after the others in its tier. */
const foreign = (cat: string, name: string) => !['', 'EN'].includes(readName(name, cat).lang)

/** Each TMDB film's best catalogue copy (by [tier], other languages last) as [VOD id, container], by title id. */
export async function bestFilmCopies(cfg: XtreamConfig): Promise<Map<string, [number, string]>> {
  const { cats, films } = await vodCatalogue(cfg)
  const best = new Map<string, [number, string, number]>()
  for (const { id, name, cat, ext, tmdb } of films) {
    const title = filmTitle(tmdb, id)
    if (!title.startsWith('movie:')) continue
    const rank = tier(cats[cat] ?? '', name) * 2 + Number(foreign(cats[cat] ?? '', name))
    if (rank < (best.get(title)?.[2] ?? Infinity)) best.set(title, [id, ext, rank])
  }
  return new Map([...best].map(([t, [id, ext]]) => [t, [id, ext]]))
}

/**
 * Films in the whole catalogue whose title has every typed word, one per film (same title and year; a
 * copy with no year joins a dated one of the same title), each on its best copy; titles where every
 * word starts a word come first, then by the copy's tier, other languages last, then newest. At most [limit].
 */
export async function searchVod(cfg: XtreamConfig, query: string, limit = 40): Promise<VodItem[]> {
  const words = norm(query).split(' ').filter(Boolean)
  if (!words.length) return []
  const { cats, films } = await vodCatalogue(cfg)
  type Hit = { item: VodItem; key: string; starts: boolean; tier: number; foreign: boolean; added: number }
  const better = (a: Hit, b: Hit) => a.tier - b.tier || Number(a.foreign) - Number(b.foreign) || b.added - a.added
  const best = new Map<string, Hit>()
  for (const { id, name, icon: poster, cat, ext, added, tmdb } of films) {
    const clean = cleanName(name)
    const title = norm(clean.replace(YEAR, ''))
    if (!words.every((w) => title.includes(w))) continue
    const catName = cats[cat] ?? ''
    const key = `${title.replace(/^[a-z]{2} /, '')}|${clean.match(YEAR)?.[1] ?? ''}`
    const hit: Hit = {
      item: { id, titleId: filmTitle(tmdb, id), name: clean, poster, group: claims4K(catName) ? movieGroup(catName) : BLURAY.test(catName) ? BLURAY_GROUP : `🎬 ${plainCategory(catName)}`, ext },
      key,
      starts: words.every((w) => ` ${title}`.includes(` ${w}`)),
      tier: tier(catName, name),
      foreign: foreign(catName, name),
      added,
    }
    const was = best.get(key)
    if (!was || better(hit, was) < 0) best.set(key, hit)
  }
  const dated = new Set([...best.keys()].filter((k) => !k.endsWith('|')).map((k) => k.slice(0, k.lastIndexOf('|'))))
  return [...best.values()]
    .filter((h) => !h.key.endsWith('|') || !dated.has(h.key.slice(0, -1)))
    .sort((a, b) => Number(b.starts) - Number(a.starts) || better(a, b))
    .slice(0, limit)
    .map((h) => h.item)
}
