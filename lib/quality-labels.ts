// Ready-to-show quality labels for films and episodes ("4K", "Dolby Vision", "HEVC", "20 Mbps",
// "Atmos", "E-AC3 5.1"), best first, unknowns left out. Episodes carry the provider's probe of the
// stream (get_series_info); films carry only a bitrate, so theirs come from the category's own tags
// ("NETFLIX MOVIES ⁴ᴷ ³⁸⁴⁰ᴾ ᴰᵒˡᵇʸ ⱽᶦˢᶦᵒⁿ", "TOP MOVIES BLURAY").
import { claims4K } from './provider.js'

/** The provider's ffprobe-style stream description, as far as labels need it. */
export interface ProbedStream {
  video?: {
    codec_name?: string
    codec_tag_string?: string
    width?: number | string
    height?: number | string
    color_transfer?: string
    r_frame_rate?: string
    avg_frame_rate?: string
    side_data_list?: { side_data_type?: string; dv_profile?: number }[]
  }
  audio?: { codec_name?: string; codec_long_name?: string; profile?: string; channels?: number | string }
  /** kbps */
  bitrate?: number | string
}

const VIDEO_CODEC: Record<string, string> = { hevc: 'HEVC', h265: 'HEVC', h264: 'H.264', avc: 'H.264', av1: 'AV1', vp9: 'VP9', mpeg2video: 'MPEG-2' }
const AUDIO_CODEC: Record<string, string> = { eac3: 'E-AC3', ac3: 'AC3', truehd: 'TrueHD', aac: 'AAC', dts: 'DTS', opus: 'Opus', mp3: 'MP3', flac: 'FLAC', mp2: 'MP2' }
const LAYOUT: Record<number, string> = { 1: 'Mono', 2: 'Stereo', 6: '5.1', 8: '7.1' }

function resolution(width: number, height: number): string | null {
  if (height >= 2160 || width >= 3800) return '4K'
  if (height >= 1080 || width >= 1900) return '1080p'
  if (height >= 720 || width >= 1260) return '720p'
  return height > 0 ? `${height}p` : null
}

/** "20 Mbps", "7.4 Mbps"; null when unknown. */
export function bitrateLabel(kbps: number | string | undefined): string | null {
  const mbps = Number(kbps) / 1000
  if (!(mbps > 0)) return null
  return `${mbps < 10 ? mbps.toFixed(1) : Math.round(mbps)} Mbps`
}

/** Labels from a probed stream (an episode's). */
export function streamLabels(s: ProbedStream | undefined): string[] {
  if (!s) return []
  const v = s.video ?? {}
  const a = s.audio ?? {}
  const out: (string | null)[] = []
  out.push(resolution(Number(v.width) || 0, Number(v.height) || 0))
  const dv = /dvhe|dvh1|dvav|dva1/i.test(`${v.codec_tag_string ?? ''} ${v.codec_name ?? ''}`) || (v.side_data_list ?? []).some((d) => /dovi|dolby vision/i.test(d.side_data_type ?? ''))
  out.push(dv ? 'Dolby Vision' : v.color_transfer === 'smpte2084' ? 'HDR10' : v.color_transfer === 'arib-std-b67' ? 'HLG' : null)
  const [num, den] = String(v.avg_frame_rate || v.r_frame_rate || '').split('/').map(Number)
  const fps = den ? num / den : num
  out.push(fps >= 48 ? `${Math.round(fps)} fps` : null)
  out.push(VIDEO_CODEC[String(v.codec_name ?? '').toLowerCase()] ?? null)
  out.push(bitrateLabel(s.bitrate))
  const audioText = `${a.profile ?? ''} ${a.codec_long_name ?? ''}`
  if (/atmos|joc/i.test(audioText)) out.push('Atmos')
  const codec = /dts-hd ma/i.test(audioText) ? 'DTS-HD MA' : AUDIO_CODEC[String(a.codec_name ?? '').toLowerCase()]
  const layout = LAYOUT[Number(a.channels)] ?? null
  if (codec || layout) out.push([codec, layout].filter(Boolean).join(' '))
  return out.filter((l): l is string => !!l)
}

/** Labels for a film from its category's tags and its bitrate (kbps). Tags read through their superscript spelling. */
export function categoryLabels(category: string, kbps?: number | string): string[] {
  const c = category.normalize('NFKC').replace(/ɪ/g, 'i')
  const out: (string | null)[] = []
  out.push(claims4K(c) ? '4K' : /blu-?ray/i.test(c) ? '1080p' : null)
  out.push(/dolby\s*vis|\bDV\b/i.test(c) ? 'Dolby Vision' : /\bHDR/i.test(c) ? 'HDR' : null)
  out.push(bitrateLabel(kbps))
  if (/dolby\s*audio/i.test(c)) out.push('Dolby Audio')
  return out.filter((l): l is string => !!l)
}

/**
 * The one-word verdict on a picture, from its resolution and how many bits each pixel gets (bitrate
 * over pixels a second, scaled by how efficient the codec is: HEVC needs about half H.264's bits for
 * the same picture). Resolution caps it (1080p tops out at Great); null when the bitrate is unknown.
 */
export type Tier = 'Reference' | 'Great' | 'OK' | 'Soft'
export interface Picture { width: number; height: number; fps?: number; codec?: string; kbps?: number | string }
const TIERS: Tier[] = ['Reference', 'Great', 'OK', 'Soft']
const CODEC_GAIN: Record<string, number> = { h264: 1, hevc: 2, vp9: 1.8, av1: 2.4, mpeg2video: 0.6 }
const is4K = (p: Picture) => p.height >= 2000 || p.width >= 3800

export function pictureTier(p: Picture): Tier | null {
  const kbps = Number(p.kbps)
  if (!(kbps > 0) || !(p.width > 0 && p.height > 0)) return null
  // Unknown codec: 4K is HEVC in practice, smaller pictures H.264.
  const gain = CODEC_GAIN[String(p.codec ?? '').toLowerCase()] ?? (is4K(p) ? 2 : 1)
  const fps = p.fps && p.fps > 0 ? p.fps : 24
  // H.264-equivalent bits per pixel: 4K HEVC at 25 Mbps is 0.25, at 16 Mbps (Netflix's 4K) 0.16.
  const bpp = (kbps * 1000 * gain) / (p.width * p.height * fps)
  const byBits = bpp >= 0.24 ? 0 : bpp >= 0.14 ? 1 : bpp >= 0.07 ? 2 : 3
  const cap = is4K(p) ? 0 : p.height >= 1000 || p.width >= 1900 ? 1 : p.height >= 700 ? 2 : 3
  return TIERS[Math.max(byBits, cap)]
}

/** A probed stream's picture (an episode's, or a film's measured by the app). */
export function probedPicture(s: ProbedStream | undefined): Picture | null {
  const v = s?.video
  if (!v) return null
  const [num, den] = String(v.avg_frame_rate || v.r_frame_rate || '').split('/').map(Number)
  return { width: Number(v.width) || 0, height: Number(v.height) || 0, fps: den ? num / den : num || undefined, codec: v.codec_name, kbps: s?.bitrate }
}

/** What a film's category claims its picture is (no codec or frame rate): the estimate until it is measured. */
export function claimedPicture(category: string, kbps?: number | string): Picture | null {
  if (claims4K(category)) return { width: 3840, height: 2160, kbps }
  if (/blu-?ray/i.test(category)) return { width: 1920, height: 1080, kbps }
  return null
}

/** A film as the app measured it while it played: what the player decoded, named as ffprobe names it. */
export interface Measured { w: number; h: number; fps?: number; codec: string; hdr?: string; audio?: string; channels?: number; atmos?: boolean; /** file size over running time */ kbps?: number }

/** The measured film as a probed stream, so it gets the same labels and tier as an episode. */
export function measuredStream(m: Measured, kbps?: number | string): ProbedStream {
  return {
    video: {
      codec_name: m.codec, width: m.w, height: m.h,
      codec_tag_string: m.hdr === 'Dolby Vision' ? 'dvhe' : '',
      color_transfer: m.hdr === 'HDR10' ? 'smpte2084' : m.hdr === 'HLG' ? 'arib-std-b67' : '',
      avg_frame_rate: m.fps ? String(m.fps) : '',
    },
    audio: { codec_name: m.audio, channels: m.channels, profile: m.atmos ? 'Atmos' : '' },
    bitrate: kbps,
  }
}
