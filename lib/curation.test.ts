import { describe, expect, spyOn, test } from 'bun:test'
import {
  KEEP,
  autoIncluded,
  bucketForCategory,
  buildEventEpg,
  oneSchedule,
  chanKey,
  couldBeLiveMatch,
  fetchMyepgGuide,
  isHomeProvider,
  jevCompetitions,
  learnedCompetitions,
  replayAirings,
  qualityScore,
  saneFps,
  staticCompetitions,
  withCompetitionGroups,
  type Channel,
} from './curation.js'

// Learned competitions come from a fixture, never the learner's live output.
process.env.COMPETITIONS_FILE = `${import.meta.dir}/fixtures/competitions.json`

describe('sports playlist curation', () => {
  test('does not reintroduce expired 2026 tournament packages', () => {
    const staleIds = [2343, 2361, 2352, 2346, 2353, 2354, 2355, 2362, 1958, 1334, 2334]
    expect(staleIds.some((id) => KEEP.includes(id))).toBe(false)
    expect(autoIncluded('8K| WORLD CUP 2026 8K')).toBe(false)
    expect(autoIncluded('ES| DAZN MUNDIAL PPV')).toBe(false)
    expect(autoIncluded('FR| ROLAND GARROS 2026 RAW')).toBe(false)
  })

  test('keeps current high-quality beIN families in Arabic sports', () => {
    const highQualityBeinIds = [1134, 780, 346, 781, 1133, 349]
    expect(highQualityBeinIds.every((id) => KEEP.includes(id))).toBe(true)
    expect(bucketForCategory('AR| BEIN SPORTS 8K & RAW')).toBe('🇸🇦 beIN & Arabic Sports')
    expect(qualityScore('ᵁᴴᴰ: beIN Sports 1 HD', 'AR| BEIN SPORTS UHD')).toBe(3)
    expect(qualityScore('ᵁᴴᴰ: beIN Sports 1 ˢᵈ', 'AR| BEIN SPORTS UHD')).toBe(0)
    expect(qualityScore('SS: beIN Sports 1 4K', 'AR| BEIN SPORTS SS')).toBe(5)
  })

  test('continues to discover newly added current football categories', () => {
    expect(autoIncluded('UK| UEFA CHAMPIONS LEAGUE PPV')).toBe(true)
    expect(autoIncluded('DE| BUNDESLIGA PPV')).toBe(true)
    // the provider writes the French league's channel without a space
    expect(autoIncluded('FR| LIGUE1+ ᴮᴱ')).toBe(true)
    expect(bucketForCategory('FR| LIGUE1+ ᴮᴱ')).toBe('🇫🇷 French Sports')
  })

  test('KEEP\'s ids apply only on the provider they were picked from', () => {
    expect(isHomeProvider(new Map([[333, 'DE| SPORT HD/4K'], [870, 'ES| M+ LALIGA VIP'], [6, 'RU| RENAMED']]))).toBe(true)
    expect(isHomeProvider(new Map([[333, 'DE | SPORT'], [870, 'US | NBA'], [6, 'RU| RUSSIAN HD/4K']]))).toBe(false)
    expect(autoIncluded('DE | BUNDESLIGA')).toBe(true)
    expect(bucketForCategory('ES: LALIGA')).toBe('🇪🇸 Spanish Sports')
  })
})

describe('sibling feeds', () => {
  test('chanKey collapses a channel\'s feed families onto one identity', () => {
    expect(chanKey('VIP: TNT SPORTS 1 ᴿᴬᵂ ⁵⁰ FPS')).toBe('tnt sport 1')
    expect(chanKey('NOW: TNT SPORT 1 ᴴᴰ')).toBe('tnt sport 1')
    expect(chanKey('UK: TNT SPORTS 1 HEVC 4K')).toBe('tnt sport 1')
    expect(chanKey('DE: SKY SPORT BUNDESLIGA 1 HD (SAT)')).toBe('sky sport bundesliga 1')
    expect(chanKey('SKYGO: SKY SPORT BUNDESLIGA 1 4K')).toBe('sky sport bundesliga 1')
    // superscript words survive as words, so Arena stays distinct from Sky Sports+
    expect(chanKey('UK: SKY SPORTS ᴬʳᵉⁿᵃ HD')).toBe('sky sport arena')
    expect(chanKey('NOW: SKY SPORTS+ ᴿᴬᵂ')).toBe('sky sport')
  })

  test('EPG: an id the guides lack borrows its sibling feed\'s programmes', () => {
    const uk = '🏴 Sky Sports & UK Sports'
    const ch = (streamId: number, name: string, tvgId: string): Channel =>
      ({ streamId, name, logo: '', tvgId, group: uk, isEventSlot: false, q: 4 })
    const channels = [
      ch(1, 'UK: TNT SPORTS 1 ᴿᴬᵂ', 'TNTSport1.uk'),
      ch(2, 'NOW: TNT SPORT 1 ᴴᴰ', 'tntsports1.uk'),
      ch(3, 'UK: SKY SPORTS NEWS HD', 'SkySportsNews.uk'),
      ch(4, 'UK: SKY SPORTS MAIN EVENT HD', 'SkySportsNews.uk'), // provider mislabel
      ch(5, 'NOW: SKY SPORTS MAIN EVENT', 'sx.5'),
    ]
    const prog = (id: string, title: string) =>
      `<programme start="20260920180000 +0000" stop="20260920190000 +0000" channel="${id}"><title>${title}</title></programme>`
    const xml = buildEventEpg(channels, '', {
      programmes: [prog('tntsports1.uk', 'Live Serie A: Juventus v Atalanta'), prog('SkySportsNews.uk', 'Sky Sports News')],
      covered: new Set(['tntsports1.uk', 'SkySportsNews.uk']),
    })
    expect(xml).toContain('channel="TNTSport1.uk"><title>Live Serie A: Juventus v Atalanta</title>')
    expect(xml).not.toContain('<title>UK: TNT SPORTS 1 ᴿᴬᵂ</title>')
    // an id the provider also stamps on a differently-named channel never donates
    expect(xml).not.toContain('channel="sx.5"><title>Sky Sports News</title>')
    expect(xml).toContain('channel="sx.5"><title>NOW: SKY SPORTS MAIN EVENT</title>')
  })

  test('EPG: guides on the late Qatari clock are moved 3h earlier', () => {
    const ch = (streamId: number, tvgId: string): Channel =>
      ({ streamId, name: `beIN ${streamId}`, logo: '', tvgId, group: '🇸🇦 beIN & Arabic Sports', isEventSlot: false, q: 4 })
    const prog = (id: string, start: string, title = 'Sabah vs Dortmund') =>
      `<programme start="${start}" stop="${start}" channel="${id}"><title>${title}</title></programme>`
    // provider: mixed-case id on the late clock, lowercase twin on the right one
    const provider = prog('beINSports1En.qa', '20261020210000 +0200') + prog('beinsports2.qa', '20261020183000 +0200', 'PSG vs Barcelona')
    // myepg mirrors of each clock, already re-tagged to our ids (and in UTC)
    const myepg = {
      programmes: [prog('sx.1', '20261020190000 +0000'), prog('sx.2', '20261020163000 +0000', 'PSG vs Barcelona')],
      covered: new Set(['sx.1', 'sx.2']),
    }
    const xml = buildEventEpg([ch(1, 'beINSports1En.qa'), ch(2, 'beinsports2.qa'), ch(3, 'sx.1'), ch(4, 'sx.2')], provider, myepg)
    expect(xml).toContain('start="20261020210000 +0500" stop="20261020210000 +0500" channel="beINSports1En.qa"')
    expect(xml).toContain('start="20261020183000 +0200" stop="20261020183000 +0200" channel="beinsports2.qa"')
    expect(xml).toContain('start="20261020190000 +0300" stop="20261020190000 +0300" channel="sx.1"')
    expect(xml).toContain('start="20261020163000 +0000" stop="20261020163000 +0000" channel="sx.2"')
  })

  test('EPG: the provider continues a channel where its myepg schedule runs out', () => {
    const prog = (start: string, stop: string, title: string, off = '+0000') =>
      `<programme start="${start} ${off}" stop="${stop} ${off}" channel="B1.qa"><title>${title}</title></programme>`
    // myepg on the right clock; the provider's mixed-case id 3h late, listing the channel twice
    const provider = prog('20261108200000', '20261108220000', 'Man Utd vs Villa (provider)') +
      prog('20261108220000', '20261109010000', 'Atletico vs Barcelona') + prog('20261108221500', '20261109011500', 'Atletico vs Barcelona (2nd listing)') +
      prog('20261109010000', '20261109020000', 'Highlights') + prog('20261109020000', '20261110000000', 'Real Madrid vs Getafe - LaLiga')
    const xml = buildEventEpg([{ streamId: 1, name: 'beIN 1', logo: '', tvgId: 'B1.qa', group: 'ar', isEventSlot: false, q: 4 }], provider, {
      programmes: [prog('20261108160000', '20261108170000', 'Palace vs Liverpool'), prog('20261108170000', '20261108190000', 'Man Utd vs Villa')],
      covered: new Set(['B1.qa']),
      icons: new Map(),
    })
    expect(xml).toContain('start="20261108170000 +0000" stop="20261108190000 +0000" channel="B1.qa"><title>Man Utd vs Villa</title>')
    expect(xml).not.toContain('(provider)')
    expect(xml).toContain('start="20261108220000 +0300" stop="20261109010000 +0300" channel="B1.qa"><title>Atletico vs Barcelona</title>')
    expect(xml).not.toContain('(2nd listing)')
    expect(xml).toContain('<title>Highlights</title>')
    // the last listing runs to the end of the provider's guide: a 🏆 match is cut to 3 h
    expect(xml).toContain('start="20261109020000 +0300" stop="20261109020000 +0000" channel="B1.qa"><title>Real Madrid vs Getafe - LaLiga</title>')
  })

  test("EPG: a 🏆 match's listing runs at most 3 h, wherever it comes from; other sports keep theirs", () => {
    const prog = (title: string) => `<programme start="20261108193000 +0000" stop="20261109180000 +0000" channel="B1.qa"><title>${title}</title></programme>`
    const xml = buildEventEpg([{ streamId: 1, name: 'beIN 1', logo: '', tvgId: 'B1.qa', group: 'ar', isEventSlot: false, q: 4 }], prog('Detroit Lions @ Miami Dolphins - NFL'), {
      programmes: [prog('Atletico Madrid vs Barcelona - Spanish LaLiga 2026/2027 - Week 12')],
      covered: new Set(['B1.qa']),
      icons: new Map(),
    })
    expect(xml).toContain('stop="20261108223000 +0000" channel="B1.qa"><title>Atletico Madrid vs Barcelona')
    expect(buildEventEpg([{ streamId: 1, name: 'x', logo: '', tvgId: 'B1.qa', group: 'ar', isEventSlot: false, q: 4 }], prog('Detroit Lions @ Miami Dolphins - NFL')))
      .toContain('stop="20261109180000 +0300" channel="B1.qa"><title>Detroit Lions')
  })

  test('EPG: each channel keeps one schedule', () => {
    const p = (start: string, stop: string, title: string) => `<programme start="20261108${start}00 +0000" stop="20261108${stop}00 +0000" channel="x"><title>${title}</title></programme>`
    const titles = (bs: string[]) => bs.map((b) => `${b.match(/start="\d{8}(\d{4})/)![1]}-${b.match(/stop="\d{8}(\d{4})/)![1]} ${b.match(/<title>([^<]*)/)![1]}`)
    // the same listing twice
    expect(titles(oneSchedule([p('1900', '2100', 'Snooker'), p('1900', '2100', 'Snooker'), p('2100', '2200', 'Riding'), p('2100', '2200', 'Riding')])))
      .toEqual(['1900-2100 Snooker', '2100-2200 Riding'])
    // the programme on air given a made-up hour
    expect(titles(oneSchedule([p('2015', '2045', 'Report'), p('2045', '2145', 'Five reasons'), p('2100', '2115', 'News'), p('2115', '2130', 'Sci-fi')])))
      .toEqual(['2015-2045 Report', '2045-2100 Five reasons', '2100-2115 News', '2115-2130 Sci-fi'])
    // two listings interleaved: the longer one wins, the other only fills its gaps
    expect(titles(oneSchedule([p('1330', '1630', 'Palace'), p('1630', '1950', 'Man Utd'), p('1950', '2300', 'Atletico'),
      p('1300', '1600', 'Palace B'), p('1600', '1930', 'Man Utd B'), p('2300', '2330', 'Moments B')])))
      .toEqual(['1300-1330 Palace B', '1330-1630 Palace', '1630-1950 Man Utd', '1950-2300 Atletico', '2300-2330 Moments B'])
  })

  test('EPG: guide ids match in any case, and Jev matches feed further channels', async () => {
    const ar = '🇸🇦 beIN & Arabic Sports'
    const ch = (streamId: number, name: string, tvgId: string): Channel =>
      ({ streamId, name, logo: '', tvgId, group: ar, isEventSlot: false, q: 4 })
    const prog = (id: string, title: string) =>
      `<programme start="20261002180000 +0000" stop="20261002190000 +0000" channel="${id}"><title>${title}</title></programme>`
    // The guide lists Sky Sport Bundesliga 10 twice; the entry with our exact name has no programmes.
    let guide = `<tv><channel id="beinsports3.fr"><display-name>BEIN SPORTS 3</display-name><icon src="https://l/bein3.png"/></channel>` +
      `<channel id="alkassone.qa"><display-name>Alkass One</display-name><icon src="https://l/alkass1.png"/></channel>` +
      `<channel id="skysportbundesliga10.de"><display-name>DE: SKY SPORT BUNDESLIGA 10 (MOBIL)</display-name></channel>` +
      // lists our feeds' exact names (TNT's carry a different id); the placeholder logo is no logo
      `<channel id="tntsports1.uk"><display-name>TNT Sports 1</display-name><display-name>UK: TNT SPORTS 1 &amp; ᴿᴬᵂ</display-name><icon src="https://l/tnt1.png"/></channel>` +
      `<channel id="x.qa"><display-name>F: ALKASS 1 ᴿᴬᵂ</display-name><icon src="https://logos.myepg.top/myepg-logo.png"/></channel>` +
      `<channel id="Sky.Sport.Bundesliga.10.de"><display-name>Sky Sport Bundesliga 10</display-name></channel>` +
      prog('beinsports3.fr', 'Ligue 1: Lens v Nice') + prog('alkassone.qa', 'QSL: Al Sadd v Al Duhail') +
      prog('Sky.Sport.Bundesliga.10.de', 'BL: FC Bayern München - VfB Stuttgart') + '</tv>'
    const fetchSpy = spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(Bun.gzipSync(guide)))
    const channels = [
      ch(1, '8K: beIN SP⚽RTS 3 FRANCE ᴿᴬᵂ', 'BeinSports3.fr'), ch(2, 'SPO: Al Kass 1 4K', 'AlKassOne.qa'),
      ch(3, 'F: ALKASS 1 ᴿᴬᵂ', 'sx.3'), ch(4, 'DE: SKY SPORT BUNDESLIGA 10 (MOBIL)', 'SkyBundesliga10.de'),
      ch(5, 'UK: TNT SPORTS 1 & ᴿᴬᵂ', 'TNTSport1.uk'), ch(6, 'FR: CANAL+ SPORT', 'BeinSports3.fr'),
    ]
    const channelOf = (p: string) => p.match(/channel="([^"]*)"/)?.[1]
    try {
      const guided = async (matches: Record<string, string[]>) =>
        (await fetchMyepgGuide(['https://guide.example/epg.xml.gz'], channels, matches)).programmes.map(channelOf)
      expect(await guided({})).toEqual(['BeinSports3.fr', 'AlKassOne.qa'])
      const jev = { 'alkassone.qa': ['sx.3'], 'Sky.Sport.Bundesliga.10.de': ['SkyBundesliga10.de'] }
      expect(await guided(jev)).toEqual(['BeinSports3.fr', 'AlKassOne.qa', 'sx.3', 'SkyBundesliga10.de'])
      // the guide's logos follow the same matches, and the guide's exact names of our feeds (a channel
      // without an <icon> keeps the provider's, and so does an id the provider stamps on two channels)
      expect((await fetchMyepgGuide(['https://guide.example/epg.xml.gz'], channels, jev)).icons).toEqual(new Map([
        ['AlKassOne.qa', 'https://l/alkass1.png'], ['sx.3', 'https://l/alkass1.png'],
        ['TNTSport1.uk', 'https://l/tnt1.png'], ['UK: TNT SPORTS 1 & ᴿᴬᵂ', 'https://l/tnt1.png'],
      ]))
      // once the duplicate entry carries programmes too, only the first feeder counts
      guide = guide.replace('</tv>', prog('skysportbundesliga10.de', 'BL: Schalke 04 - Bayern') + '</tv>')
      expect(await guided(jev)).toEqual(['BeinSports3.fr', 'AlKassOne.qa', 'sx.3', 'SkyBundesliga10.de'])
    } finally {
      fetchSpy.mockRestore()
    }
  })
})

describe('competition groups', () => {
  test('names and categories place channels; other sports and women\'s leagues do not', () => {
    expect(staticCompetitions('UK: SERIE A - AC MILAN 4K', 'UK| SERIE A TEAM PPV')).toEqual(['seriea'])
    expect(staticCompetitions('IT: ZONA DAZN SERIE A 1 4K', 'IT| SERIE A/B/C')).toEqual(['seriea'])
    expect(staticCompetitions('IT: TIM VISION SERIE A FEMMINILE 1', 'IT| SERIE A/B/C')).toEqual([])
    expect(staticCompetitions('IT: ALLIANZ MILANO -SUPERLEGA SERIE A MASCHILE-', 'IT| SERIE A/B/C')).toEqual([])
    expect(staticCompetitions('IT: HIGHLIGHTS SERIE A HD', 'IT| SPORT HD/4K')).toEqual([])
    expect(staticCompetitions('IT: DAZN DIRETTA GOL SERIE B HD', 'IT| SERIE A/B/C')).toEqual([])
    expect(staticCompetitions('IT: GIUGLIANO GIRONE C', 'IT| SERIE A/B/C')).toEqual([])
    expect(staticCompetitions('UK: SKY SPORTS PL ᴿᴬᵂ', 'UK| SPORT ᴿᴬᵂ')).toEqual(['pl'])
    expect(staticCompetitions('IT: Eleven Sport Pallamano Bundesliga Live + Replica', 'IT| SPORT HD/4K')).toEqual([])
    expect(staticCompetitions('ES: LALIGA TV HYPERMOTION ᴿᴬᵂ', 'ES| M+ LALIGA VIP')).toEqual([])
    expect(staticCompetitions('ES: M+ LIGA DE CAMPEONES 2 ᴿᴬᵂ', 'ES| M+ LIGA DE CAMPEONES VIP')).toEqual(['ucl'])
    expect(staticCompetitions('UK: VIDIO EVENT 1', 'UK| VIDIO EPL PPV')).toEqual(['pl'])
    expect(staticCompetitions('CHAMP: BIRMINGHAM CITY', 'UK| CHAMPIONSHIP PPV')).toEqual(['champ'])
    expect(staticCompetitions('FR: LIGUE1+ 1 ⱽᴵᴾ ᴿᴬᵂ', 'FR| LIGUE1+ ⱽᴵᴾ ᴿᴬᵂ')).toEqual(['ligue1'])
    // a bare UEFA package can't tell the three cups apart, so it joins all of them
    expect(staticCompetitions('UEFA | 03 - Beşiktaş vs Marseille 8:00 pm', 'UK| UEFA PPV')).toEqual(['ucl', 'uel', 'uecl'])
    // other countries' Premier Leagues
    expect(staticCompetitions('Next | St. Louis FC vs. PTL Bazar Brothers FC | Premier League - Seychelles | 2026-11-05 | 14:30 (GMT) | DE: DAZN PPV 5', 'DAZN PPV')).toEqual([])
  })

  test('EPG titles count only when they are live matches', () => {
    expect(learnedCompetitions('Live Serie A: Juventus v Atalanta')).toEqual(['seriea'])
    expect(learnedCompetitions('Nottingham Forest vs  Coventry City - English Premier League 2026/2027')).toEqual(['pl'])
    expect(learnedCompetitions('Bundesliga - Bayer 04 Leverkusen - RB Leipzig ᴸᶦᵛᵉ')).toEqual(['bundes'])
    expect(learnedCompetitions('UEFA Europa League: Juventus - NEC')).toEqual(['uel'])
    expect(learnedCompetitions('Serie A Preview Show Magazine:Round 5')).toEqual([])
    expect(learnedCompetitions('Premier League Review')).toEqual([])
    expect(learnedCompetitions('Brasileiro Serie A: Santos v Cruzeiro')).toEqual([])
    expect(learnedCompetitions('Frauen-Bundesliga - FC Bayern München - 1. FC Köln')).toEqual([])
    expect(learnedCompetitions('Es folgt: Live 2. Bundesliga')).toEqual([])
    expect(learnedCompetitions('Bundesliga Highlights - 4. Spieltag')).toEqual([])
    expect(learnedCompetitions('EPL Netbusters - 2026/2027 EP 4')).toEqual([])
    expect(learnedCompetitions('Serie A - Full Impact')).toEqual([])
    // retro replays carry an old season tag
    expect(learnedCompetitions('EFL Cup 07/08: Chelsea v Spurs', new Date('2026-09-20'))).toEqual([])
    expect(learnedCompetitions('Goal Rush: Champions League 2017/18', new Date('2026-09-20'))).toEqual([])
    expect(learnedCompetitions('Manchester City vs Norwich - Carabao Cup 2026 / 2027 - Round 3', new Date('2026-09-20'))).toEqual(['eflcup'])
    // Arabic guides write fixtures as "X × Y"
    expect(learnedCompetitions('الدوري الإيطالي:روما × إنتر ميلان')).toEqual(['seriea'])
  })

  test('only match-like titles go to Jev', () => {
    expect(couldBeLiveMatch('Udinese - Cagliari')).toBe(true)
    expect(couldBeLiveMatch('BL: FC Bayern München - VfB Stuttgart, 1. Spieltag')).toBe(true)
    expect(couldBeLiveMatch('Premier League Review')).toBe(false)
    expect(couldBeLiveMatch('Premier League Icons - Yaya Toure')).toBe(false)
    // an event channel announcing a later match isn't airing it
    expect(couldBeLiveMatch('Next | Sunderland vs. Dinamo Zagreb | all | 05-11-2026 | 20:45 (GMT) | DE: SOCCER PPV 68')).toBe(false)
    expect(couldBeLiveMatch('EFL Cup 07/08: Chelsea v Spurs', new Date('2026-09-20'))).toBe(false)
  })

  test('re-runs: a fixture airing after its first full airing or its highlights; live airings never', () => {
    const h = 3600
    const air = (tvgId: string, title: string, start: number, hours = 2) => ({ tvgId, title, start: start * h, stop: (start + hours) * h })
    const rerun = (airings: ReturnType<typeof air>[]) => [...replayAirings(airings)].sort()
    // Simulcast live on several channels, even when one starts its coverage later: all live.
    expect(rerun([air('a', 'Liverpool v Arsenal', 10), air('b', 'Premier League: Liverpool - Arsenal', 11, 1.5)])).toEqual([])
    // Once the first airing ended, the next one (any title) is a re-run; so is one after the highlights.
    expect(rerun([air('a', 'Liverpool v Arsenal', 10), air('b', 'Liverpool vs Arsenal - English Premier League', 13)])).toEqual([1])
    expect(rerun([air('a', 'MATCH HIGHLIGHTS Liverpool v Arsenal', 9, 0.5), air('b', 'Liverpool v Arsenal: The Match', 10)])).toEqual([1])
    // Other fixtures of the same clubs are not the same match.
    expect(rerun([air('a', 'Real Madrid v Real Betis', 10), air('b', 'Real Madrid v Real Sociedad', 13)])).toEqual([])
    // An event channel's hourly blocks are one airing; its "Next |" announcements and previews aren't airings.
    expect(rerun([
      air('ppv', 'Live | Senegal - Argentina | Futsal', 10, 1), air('ppv', 'Live | Senegal - Argentina | Futsal', 11, 1),
      air('ppv2', 'Next | Deportivo vs. Osasuna | 20:00 (GMT)', 10, 9),
      air('es', 'La Casa del Fútbol Previa - RC Deportivo - CA Osasuna', 19.5, 0.25),
      air('es', 'LaLiga EA Sports - Deportivo de La Coruña - Osasuna', 19.9),
    ])).toEqual([])
    // Tagged live is live.
    expect(rerun([air('a', 'Liverpool v Arsenal', 10), air('b', 'Live: Liverpool v Arsenal', 13)])).toEqual([])
    // A channel's title template is not a fixture; the same clubs in another competition are another match.
    expect(rerun([air('pl', 'Liga włoska - mecz: US Sassuolo Calcio - ACF Fiorentina', 10), air('pl', 'Liga włoska - mecz: Atalanta BC - Parma Calcio 1913', 13)])).toEqual([])
    expect(rerun([air('a', 'Everton vs Newcastle United - Carabao Cup', 10), air('b', 'Newcastle United vs Everton - English Premier League', 30)])).toEqual([])
  })

  test('Jev verdicts count only confident live men\'s matches; no key means the regexes decide', async () => {
    const pick = (choice: string, p: number) => ({ choice, probabilities: { [choice]: p } })
    const answers = {
      c0: pick('Serie A', 0.95), m0: { noul: 0.9 }, w0: { noul: 0.1 },
      c1: pick('Premier League', 0.7), m1: { noul: 0.9 }, w1: { noul: 0.1 }, // competition unsure
      c2: pick('Bundesliga', 0.99), m2: { noul: 0.9 }, w2: { noul: 0.9 }, // women's
      c3: pick('Premier League', 0.99), m3: { noul: 0.2 }, w3: { noul: 0.1 }, // not a match
      c4: pick('other football', 0.99), m4: { noul: 0.95 }, w4: { noul: 0.05 },
    }
    const fetchSpy = spyOn(globalThis, 'fetch').mockImplementation(async () =>
      new Response(JSON.stringify({ answers, usage: { input_tokens: 1 } })))
    process.env.TYPESAFE_API_KEY = 'test'
    try {
      const titles = ['Udinese - Cagliari', 'Ipswich v Hull', 'Bayern - Köln', 'PL: 90in30 - BRI - ARS', 'Al Hilal v Al Nassr']
      expect([...(await jevCompetitions(titles)).values()]).toEqual([['seriea'], [], [], [], []])
      // each title rides in its own questions, not in a shared state
      expect(JSON.parse(String(fetchSpy.mock.calls[0][1]?.body)).questions.c0.instructions).toContain('Udinese - Cagliari')
      delete process.env.TYPESAFE_API_KEY
      await expect(jevCompetitions(titles)).rejects.toThrow('TYPESAFE_API_KEY')
    } finally {
      fetchSpy.mockRestore()
      delete process.env.TYPESAFE_API_KEY
    }
  })

  test('groups lead the playlist; always-on channels first, then quality, then language', () => {
    const mk = (streamId: number, name: string, group: string, q: number, extra: Partial<Channel> = {}): Channel =>
      ({ streamId, name, logo: '', tvgId: 'x', group, isEventSlot: false, q, comps: staticCompetitions(name, ''), ...extra })
    const channels = [
      mk(900000001, 'UK: SKY SPORTS PREMIER LEAGUE HD', '🏴 Sky Sports & UK Sports', 3),
      mk(900000003, 'AR: BUNDESLIGA 1 ᴿᴬᵂ', '🇸🇦 beIN & Arabic Sports', 4),
      mk(900000004, 'DE: SKY SPORT BUNDESLIGA 1 4K', '🇩🇪 German Sports', 5),
      mk(900000005, 'DE: SKY SPORT BUNDESLIGA 1 HD', '🇩🇪 German Sports', 3),
      mk(900000006, 'DE: SKY SPORT BUNDESLIGA 1 (MOBIL)', '🇩🇪 German Sports', 3),
      mk(900000008, 'UK: EPL 1 PPV ᵁᴴᴰ ³⁸⁴⁰ᴾ', '⚽ Football — Match PPV', 5, { isEventSlot: true }),
      mk(900000002, 'UK: EPL ARSENAL', '⚽ Football — Match PPV', 2, { isEventSlot: true }),
      mk(900000007, 'DE: BUNDESLIGA REPLAY 1 HD', '📼 Football — Replays', 3),
    ]
    const out = withCompetitionGroups(channels)
    const groups = out.map((c) => c.group)
    expect(groups[0]).toBe('🏆 Premier League')
    expect(groups.lastIndexOf('🏆 Bundesliga')).toBeLessThan(groups.indexOf('🏴 Sky Sports & UK Sports'))
    // an idle 4K-marked slot never outranks a channel that is actually on air
    expect(out.filter((c) => c.group === '🏆 Premier League').map((c) => c.name))
      .toEqual(['UK: SKY SPORTS PREMIER LEAGUE HD', 'UK: EPL 1 PPV ᵁᴴᴰ ³⁸⁴⁰ᴾ', 'UK: EPL ARSENAL'])
    // 4K first, then the Arabic RAW at 1080 over the German 720p feeds; best + one spare per channel; replays never
    expect(out.filter((c) => c.group === '🏆 Bundesliga').map((c) => c.name))
      .toEqual(['DE: SKY SPORT BUNDESLIGA 1 4K', 'AR: BUNDESLIGA 1 ᴿᴬᵂ', 'DE: SKY SPORT BUNDESLIGA 1 HD'])
    expect(out.length).toBe(channels.length + 6)
  })

  test('an overflow channel (Sky Sports Golf) joins a competition only around its match', () => {
    const [tvgId, seen] = ['SkySportsGolf.uk', '2026-10-20']
    const at = (days: number) => Date.parse(seen) + days * 864e5
    const plNames = (name: string, now: number) =>
      withCompetitionGroups([{ streamId: 1, name, logo: '', tvgId, group: '🏴 Sky Sports & UK Sports', isEventSlot: false, q: 4 }], now)
        .filter((c) => c.group === '🏆 Premier League').map((c) => c.name)
    expect(plNames('NOW: SKY SPORTS GOLF ᴿᴬᵂ', at(0))).toEqual(['NOW: SKY SPORTS GOLF ᴿᴬᵂ'])
    expect(plNames('NOW: SKY SPORTS GOLF ᴿᴬᵂ', at(1))).toEqual(['NOW: SKY SPORTS GOLF ᴿᴬᵂ'])
    expect(plNames('NOW: SKY SPORTS GOLF ᴿᴬᵂ', at(3))).toEqual([])
    expect(plNames('UK: SKY SPORTS MAIN EVENT', at(3))).toEqual(['UK: SKY SPORTS MAIN EVENT'])
    expect(plNames('UK: SKY SPORTS NEWS', at(0))).toEqual([])
  })

  test('SG Hub Premier, HK Now Premier Sports and NZ Sky PL carry the Premier League', () => {
    for (const name of ['SG: HUB PREMIER 2 ᵁᴴᴰ ³⁸⁴⁰ᴾ', 'HK: NOW PREMIER SPORTS 1 4K', 'NZ: SKY SPORTS PREMIER LEAGUE 4K'])
      expect(staticCompetitions(name, '')).toEqual(['pl'])
    expect(staticCompetitions('UK: PREMIER SPORTS 1', '')).toEqual([])
    // listed only under 🏆 Premier League, and never merged with the UK channel of the same name
    const mk = (streamId: number, name: string, group: string): Channel =>
      ({ streamId, name, logo: '', tvgId: 'x', group, isEventSlot: false, q: 5, comps: staticCompetitions(name, '') })
    const out = withCompetitionGroups([
      mk(1, 'UK: SKY SPORTS PREMIER LEAGUE 4K', '🏴 Sky Sports & UK Sports'),
      mk(2, 'NOW: SKY SPORTS PREMIER LEAGUE 4K', '🏴 Sky Sports & UK Sports'),
      mk(3, 'NZ: SKY SPORTS PREMIER LEAGUE 4K', '🌏 Premier League abroad'),
    ])
    expect(out.filter((c) => c.group === '🏆 Premier League').map((c) => c.streamId).sort()).toEqual([1, 2, 3])
    expect(out.filter((c) => c.group !== '🏆 Premier League').map((c) => c.streamId)).toEqual([1, 2])
  })

  test('a 100fps reading is the 50p field rate, not a better feed', () => {
    expect(saneFps(100)).toBe(50)
    expect(saneFps(50)).toBe(50)
    expect(saneFps(120)).toBe(0)
  })
})
