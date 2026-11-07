// What a copy of a film is (KTD5), read from text: a release name ("Movie.2019.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.TrueHD.7.1.Atmos-GRP")
// or the provider's name and category ("4K-EN - Inception (2010)", "EN - MOVIES 4K 3840P"). Only what the
// stick's ranker weighs; anything not named stays unknown (0, '' or []).

export interface Attrs {
  /** Picture height class: 2160, 1080, 720, 576, 480; 0 unknown. */
  res: number
  /** HDR formats carried, in this order: 'dv', 'hdr10+', 'hdr10', 'hlg'; [] = SDR or not named. */
  hdr: string[]
  /** Dolby Vision profile when named (FEL/MEL are profile 7). */
  dvProfile?: number
  /** 'hevc' | 'h264' | 'av1' | 'vc1' | 'mpeg2'; '' unknown. */
  codec: string
  /** The best audio named: 'truehd' | 'dts-hd' | 'eac3' | 'dts' | 'ac3' | 'aac' | 'opus' | 'flac'; '' unknown. */
  audio: string
  /** Audio channels (8 = 7.1, 6 = 5.1); 0 unknown. */
  ch: number
  atmos?: true
  /** Audio languages named (ISO 639-1, or 'multi'); [] = none named, read as the original. */
  langs: string[]
  /** Subtitle languages named ('multi' for multi-subs). */
  subs?: string[]
  remux?: true
}

/** Release words naming a language. Two-letter codes are left out: they clash with ordinary words. */
const LANG_WORDS: Record<string, string> = {
  multi: 'multi', dual: 'multi', eng: 'en', english: 'en', ita: 'it', italian: 'it',
  fre: 'fr', french: 'fr', truefrench: 'fr', vff: 'fr', vfq: 'fr', vf2: 'fr', vfi: 'fr',
  ger: 'de', german: 'de', spa: 'es', spanish: 'es', castellano: 'es', latino: 'es',
  rus: 'ru', russian: 'ru', ukr: 'uk', hindi: 'hi', pol: 'pl', polish: 'pl', por: 'pt', tur: 'tr', turkish: 'tr',
  arabic: 'ar', jpn: 'ja', japanese: 'ja', kor: 'ko', korean: 'ko', chi: 'zh',
}
const SUB_WORD = /^(sub|subs|subbed|subtitles?)$/
const AUDIO: [string, RegExp][] = [
  ['truehd', /\btrue[ .-]?hd\b/],
  ['dts-hd', /\bdts[ .:-]?(?:hd|ma|x\b)/],
  ['eac3', /\b(?:ddp|dd\+|e-?ac-?3)/],
  ['dts', /\bdts\b/],
  ['ac3', /\b(?:ac-?3|dd)(?![a-z])/],
  ['aac', /aac/],
  ['opus', /\bopus/],
  ['flac', /\bflac/],
]
const CODEC: Record<string, string> = { x265: 'hevc', h265: 'hevc', hevc: 'hevc', x264: 'h264', h264: 'h264', avc: 'h264', av1: 'av1', vc1: 'vc1', mpeg2: 'mpeg2' }

/** A line from its year or resolution on: the title before it can't be mistaken for tags ("The Italian Job"). */
function tail(line: string): string {
  const i = line.search(/(?<!\d)(?:19|20)\d{2}(?!\d)|(?<!\d)(?:2160|1080|720|576|480)[pi]/i)
  return i > 0 ? line.slice(i) : line
}

export function parseRelease(text: string): Attrs {
  const t = text.normalize('NFKC').split('\n').map(tail).join('\n').toLowerCase()
  const named = Number(t.match(/(?<!\d)(2160|1080|720|576|480)[pi](?![a-z0-9])/)?.[1] ?? (/\b(?:4k|uhd)\b/.test(t) ? 2160 : 0))
  // AI upscales ("Ai-Enhanced", "AI Upscaled") are a lower-resolution source stretched up: rank them a step down.
  const res = named === 2160 && /\bai[ ._-]?(?:enhanced|upscal)|\bupscal/.test(t) ? 1080 : named
  const fel = /\b(?:fel|mel)\b/.test(t)
  const dv = fel || /\b(?:dv|dovi|dolby[ .]?vision)\b/.test(t)
  const plus = /\bhdr10(?:\+|plus)/.test(t)
  const hdr = [dv && 'dv', plus && 'hdr10+', (plus || /\bhdr(?:10)?\b/.test(t)) && 'hdr10', /\bhlg\b/.test(t) && 'hlg'].filter((h): h is string => !!h)
  // A streaming service's Dolby Vision (a WEB release) is profile 5, or 8 with an HDR10 layer named; never a disc's 7.
  const web = /\bweb(?:[ .-]?(?:dl|rip))?\b/.test(t) && !/\b(?:blu-?ray|remux|bdrip|bd)\b/.test(t)
  const profile = fel ? 7 : Number(t.match(/\b(?:dv|dovi|vision)[ .]?(?:p|profile[ .]?)(\d)\b/)?.[1] ?? 0) || (web ? (hdr.includes('hdr10') ? 8 : 5) : 0)
  const codec = CODEC[t.match(/\b(x26[45]|h[ .]?26[45]|hevc|avc|av1|vc-?1|mpeg-?2)\b/)?.[1].replace(/[ .-]/g, '') ?? ''] ?? ''
  const ch = t.match(/(?<!\d)([25-7])[ .]([01])(?!\d)/)
  const chWord = t.match(/\b([2-8])ch\b/)

  const langs = new Set<string>()
  const subs = new Set<string>()
  let inSubs = false
  const words = t.split(/[^a-z0-9+]+/)
  words.forEach((w, i) => {
    if (w === 'multisub' || w === 'multisubs' || (w === 'multi' && SUB_WORD.test(words[i + 1] ?? ''))) return void subs.add('multi')
    if (w === 'esub' || w === 'esubs') return void subs.add('en')
    if (SUB_WORD.test(w)) return void (inSubs = true)
    const lang = LANG_WORDS[w.replace(/^multi\d+$/, 'multi')]
    if (!lang) return void (inSubs = false)
    ;(inSubs ? subs : langs).add(lang)
  })
  return {
    res,
    hdr,
    ...(dv && profile ? { dvProfile: profile } : {}),
    codec,
    audio: AUDIO.find(([, re]) => re.test(t))?.[0] ?? '',
    ch: ch ? Number(ch[1]) + Number(ch[2]) : chWord ? Number(chWord[1]) : /\bstereo\b/.test(t) ? 2 : 0,
    ...(/\b(?:atmos|joc|ddpa)/.test(t) && { atmos: true as const }),
    langs: [...langs],
    ...(subs.size ? { subs: [...subs] } : {}),
    ...(/remux/.test(t) && { remux: true as const }),
  }
}

/**
 * Country flags (🇬🇧 / 🇮🇹, as Torrentio lists a release's audio) to languages; "Multi Audio" / "Dual Audio" to 'multi'. A list of more
 * than MAX_FLAGS is its subtitles' too (an iTunes WEB-DL flags 16 languages and carries English and Chinese audio): none of it counts.
 */
const MAX_FLAGS = 5
const COUNTRY_LANG: Record<string, string> = { gb: 'en', us: 'en', mx: 'es', br: 'pt', ua: 'uk', jp: 'ja', kr: 'ko', cn: 'zh', tw: 'zh', in: 'hi', sa: 'ar', gr: 'el', se: 'sv', dk: 'da', cz: 'cs', il: 'he', ir: 'fa', vn: 'vi', rs: 'sr', my: 'ms', ee: 'et', si: 'sl', no: 'no' }
export function flagLanguages(line: string): string[] {
  const out = [...line.matchAll(/([\u{1F1E6}-\u{1F1FF}])([\u{1F1E6}-\u{1F1FF}])/gu)].map((m) => {
    const cc = String.fromCharCode(m[1].codePointAt(0)! - 0x1f1e6 + 97, m[2].codePointAt(0)! - 0x1f1e6 + 97)
    return COUNTRY_LANG[cc] ?? cc
  })
  const audio = out.length > MAX_FLAGS ? [] : out
  return /(?:multi|dual) audio/i.test(line) ? ['multi', ...audio] : audio
}
