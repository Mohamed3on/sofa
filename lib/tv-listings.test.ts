import { describe, expect, test } from 'bun:test'
import { buildEventEpg, type Channel } from './curation'
import { parseListings, placeListings } from './tv-listings'

// One day, one competition, one match, as the sites lay them out (trimmed).
const page = (date: string, time: string, comp: string, match: string, channels: string[]) =>
  `<table><tr class="cabeceraTabla "><td> Partidos de hoy domingo, ${date}</td></tr>` +
  `<tr class="cabeceraCompericion"> <td colspan="5"> <img alt="${comp}" title="${comp}"/></td></tr>` +
  `<tr> <td class="hora "> ${time} </td> <td class="canales"> <div itemscope=""> <meta itemprop="name" content="${match}"/> </div>` +
  `<ul class="listaCanales">${channels.map((c) => `<li class="" title="${c}"><a href="/canal/x">${c}</a></li>`).join('')}</ul> </td></tr></table>`

describe('TV listings', () => {
  test('a row is a match at its kick-off on the site\'s clock, with its channels', () => {
    expect(parseListings(page('08/11/2026', '21:00', 'La Liga EA Sports', 'At. Madrid - FC Barcelona', ['M+ LALIGA (M54 O110)', 'LaLiga TV Bar']), 'Europe/Madrid'))
      .toEqual([{ home: 'At. Madrid', away: 'FC Barcelona', comp: 'La Liga EA Sports', start: Date.UTC(2026, 10, 8, 20), channels: ['M+ LALIGA (M54 O110)', 'LaLiga TV Bar'] }])
    // the German site's dates, the Arab site's Saudi clock
    expect(parseListings(page('08.11.2026', '23:00', 'LaLiga', 'Atl. Madrid - Barcelona', ['beIN SPORTS 1 EN']), 'Asia/Riyadh')[0].start).toBe(Date.UTC(2026, 10, 8, 20))
  })

  test('a listed match goes into its channels\' guides; one the site gives other channels comes out', () => {
    const ch = (streamId: number, name: string, tvgId: string, group: string): Channel => ({ streamId, name, logo: '', tvgId, group, isEventSlot: false, q: 4 })
    const channels = [ch(1, 'ES: MOVISTAR LALIGA ʰᵉᵛᶜ', 'mlaliga.es', '🇪🇸 Spanish Sports'), ch(2, 'VIP: LALIGA TV ᴿᴬᵂ', 'laligatv', '🏴 Sky Sports & UK Sports'),
      ch(3, 'VIP: PREMIER SPORT 1 ᴿᴬᵂ', 'ps1.uk', '🏴 Sky Sports & UK Sports')]
    const placed = placeListings(channels, [
      { home: '🇪🇸 Spanish Sports', listings: parseListings(page('08/11/2026', '21:00', 'La Liga EA Sports', 'At. Madrid - FC Barcelona', ['M+ LALIGA (M54 O110)']), 'Europe/Madrid') },
      { home: '🏴 Sky Sports & UK Sports', listings: [
        ...parseListings(page('08/11/2026', '20:00', 'LaLiga', 'Atl. Madrid - Barcelona', ['Premier Sports 1']), 'Europe/London'),
        ...parseListings(page('08/11/2026', '17:30', 'LaLiga', 'Celta Vigo - Levante', ['LaLiga TV']), 'Europe/London'),
      ] },
    ])
    const prog = (id: string, start: string, stop: string, title: string) => `<programme start="20261108${start}00 +0000" stop="20261108${stop}00 +0000" channel="${id}"><title>${title}</title></programme>`
    const myepg = {
      programmes: [prog('mlaliga.es', '1700', '2359', 'Movistar LaLiga UHD'), prog('laligatv', '2000', '2200', 'LALIGA: Atlético de Madrid vs FC Barcelona'),
        prog('ps1.uk', '1955', '2200', 'Live LALIGA: Atletico Madrid v Barcelona')],
      covered: new Set(['mlaliga.es', 'laligatv', 'ps1.uk']),
      icons: new Map(),
    }
    const programmes = (id: string) => [...buildEventEpg(channels, '', myepg, placed).matchAll(new RegExp(`<programme start="\\d{8}(\\d{4})\\d\\d \\+0000" stop="\\d{8}(\\d{4})\\d\\d \\+0000" channel="${id}"><title>([^<]*)`, 'g'))].map((m) => `${m[1]}-${m[2]} ${m[3]}`)
    // Movistar LaLiga shows it over its filler
    expect(programmes('mlaliga.es')).toEqual(['1700-2000 Movistar LaLiga UHD', '2000-2200 At. Madrid vs FC Barcelona - La Liga EA Sports', '2200-2359 Movistar LaLiga UHD'])
    // the UK site has it on Premier Sports, and knows LaLiga TV (Celta vs Levante), so LaLiga TV's claim goes, and the match the site gives it comes in
    expect(programmes('laligatv')).toEqual(['1730-1930 Celta Vigo vs Levante - LaLiga'])
    expect(programmes('ps1.uk')).toEqual(['1955-2200 Live LALIGA: Atletico Madrid v Barcelona'])
  })
})
