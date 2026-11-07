// GET /api/epg?t=TOKEN — XMLTV for the curated playlist: synthetic PPV/event
// guide (channel name as title) PLUS the provider's real programmes folded in
// for our regular channels (scoped by exact id, so the payload stays small).
import { curatedGuide } from './_lib.js'

export default async function handler(): Promise<Response> {
  return new Response((await curatedGuide()).xml, {
    headers: { 'Content-Type': 'application/xml; charset=utf-8', 'Access-Control-Allow-Origin': '*' },
  })
}
