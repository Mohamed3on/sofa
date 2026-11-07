// GET /api/playlist?t=TOKEN — trimmed M3U, rebuilt from the provider on each
// cache miss so channel names (esp. PPV event slots) stay current.
// Add &vpn=1 for a copy whose streams use the provider's VPN-only front, for watching with
// a VPN on (the normal fronts block VPN IPs, and the VPN front blocks everyone else).
// With a backup provider configured (XTREAM2_*), its feeds follow provider A's as spares.
import { buildM3U, fetchCuratedChannels, withBackupFeeds, withCompetitionGroups } from '../lib/curation.js'
import { backupConfigFromEnv, configFromEnv, guideUrl, VPN_HOST } from '../lib/provider.js'

export default async function handler(req: Request): Promise<Response> {
  const cfg = configFromEnv()
  const backup = backupConfigFromEnv()
  const channels = withCompetitionGroups(await withBackupFeeds(await fetchCuratedChannels(cfg), backup))
  const proto = req.headers.get('x-forwarded-proto') ?? 'https'
  // Host, not the client-supplied X-Forwarded-Host: the cached playlist is shared by URL.
  const host = req.headers.get('host')
  const ownEpg = `${proto}://${host}/api/epg?t=${process.env.PLAYLIST_TOKEN}`
  // EPG_URLS: comma-separated preferred EPG sources (e.g. myepg.top links);
  // falls back to the provider's xmltv. Own PPV epg is always appended.
  const preferred = (process.env.EPG_URLS ?? guideUrl(cfg)).split(',').filter(Boolean)
  const vpn = new URL(req.url).searchParams.get('vpn') === '1'
  const m3u = vpn
    ? buildM3U({ ...cfg, host: VPN_HOST }, channels, [...preferred, ownEpg], backup && { ...backup, host: backup.vpnHost })
    : buildM3U(cfg, channels, [...preferred, ownEpg], backup)
  return new Response(m3u, {
    headers: {
      'Content-Type': 'audio/x-mpegurl; charset=utf-8',
      'Content-Disposition': 'inline; filename="playlist.m3u"',
      'Access-Control-Allow-Origin': '*',
    },
  })
}
