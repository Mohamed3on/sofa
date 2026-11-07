import { expect, test } from 'bun:test'
import { createHash } from 'node:crypto'
import { allowance, detail, episodeOf, filmFile, isFilm, listingRows, namesSeason, pickFilm, readTorrent, searchRows, uploadNames, wanted, type Row } from './arabp2p'

test('a search row is the film only by its whole name, without a sequel number, in its year', () => {
  const toyStory = ['حكاية لعبة', 'Toy Story']
  expect(isFilm('حكاية لعبة [BD 2160p]', toyStory, '1995')).toBe(true)
  expect(isFilm('حكاية لعبة ٥ [مترجم + مدبلج][WEB DL][1080p][H264] Toy Story 5', toyStory, '1995')).toBe(false)
  expect(isFilm('Toy Story 5 (2026) [1080p]', ['Toy Story 5'], '2026')).toBe(true)
  expect(isFilm('Toy Story (1995) [1080p]', toyStory, '1995')).toBe(true)
  expect(isFilm('Toy Story (2026) [1080p]', toyStory, '1995')).toBe(false)
  // Arabic spelling variants and Eastern Arabic digits read alike.
  expect(isFilm('قلبا وقالبا ٢ [مدبلج][1080p]', ['قلباً وقالباً 2'], '2024')).toBe(true)
})

test('Arabic films leave six of the daily downloads to dubs, and a spent allowance waits out its window', () => {
  const now = Date.now()
  const used = (n: number) => Array.from({ length: n }, (_, i) => now - i * 60_000)
  expect(allowance({ downloads: [] }, true, now, 10)).toBe(10)
  expect(allowance({ downloads: [] }, false, now, 10)).toBe(4)
  expect(allowance({ downloads: used(4) }, false, now, 10)).toBe(0)
  expect(allowance({ downloads: used(4) }, true, now, 10)).toBe(6)
  expect(allowance({ downloads: [now - 25 * 3600_000] }, true, now, 10)).toBe(10)
  expect(allowance({ downloads: [], fullUntil: now + 60_000 }, true, now, 10)).toBe(0)
})

test('the best rows get the hashes: Egyptian dubs first, packs never', () => {
  const row = (id: string, title: string, egyptian = false, size = 2 ** 31): Row => ({ id, title, size, seeds: 5, dub: true, egyptian, link: '' })
  const rows = [
    row('1', 'البحث عن نيمو [مترجم + مدبلج][BluRay][1080p]'),
    row('2', 'البحث عن نيمو (2003) — مدبلج بالعامية المصرية 720p', true),
    row('3', 'Finding Nemo 2003 2160p DSNP WEB DL'),
    row('4', 'باقة أفلام بيكسار [1080p]'),
    row('5', 'Finding Nemo [1080p]', false, 90 * 2 ** 30),
  ]
  expect(wanted(rows).map((r) => r.id)).toEqual(['2', '3', '1'])
  expect(wanted(rows, { 3: { none: true } }).map((r) => r.id)).toEqual(['2', '1'])
})

test('listings, search results and detail pages are read for id, size, seeds, dub, link, magnet and files', () => {
  const listing = `<table id="listing_table"><tr class="torrent"><td>  <a  href="/index.php?page=torrent-details&id=116602">[الفيل الأزرق][2014][1080p][Web-DL]</a></td><td><a href="download.php?id=116602&amp;f=x.torrent"><img/></a></td><td> <a href='/index.php?page=torrents&category=41'>افلام عربيه</a> </td><td>2.52 GB</td><td style="color: green;">3</td><td style="color: red;">0</td><td>4 أسابيع</td><td><span>U</span></td></tr></table>`
  expect(listingRows(listing)).toEqual([{ id: '116602', title: '[الفيل الأزرق][2014][1080p][Web-DL]', size: Math.round(2.52 * 2 ** 30), seeds: 3, dub: false, egyptian: false, link: 'download.php?id=116602&f=x.torrent' }])

  const search = `<div class="file-item"><div class="filename"> <a name='t_url' href="index.php?page=torrent-details&amp;id=115835" class="screenshot" rel="x" ><span class="tor_link">حكاية لعبة ٥ [مترجم + مدبلج][WEB DL][1080p][H264] Toy Story 5 </span></a></div>
    <button class="download-btn"><a href="download.php?id=115835&amp;f=y.torrent">تحميل</a></button>
    <span class="category"><a name='cat_url' href='index.php?page=torrents&amp;category=102'>انمي مدبلج عربي</a> - <a name='cat_url' href='x'>افلام</a></span>
    <span class="size">6.64 GB</span><span class="green" title="Seeders">▲ <a style='color: green;' href='x'>18</a></span></div>`
  expect(searchRows(search)).toEqual([{ id: '115835', title: 'حكاية لعبة ٥ [مترجم + مدبلج][WEB DL][1080p][H264] Toy Story 5', size: Math.round(6.64 * 2 ** 30), seeds: 18, dub: true, egyptian: false, link: 'download.php?id=115835&f=y.torrent' }])

  const page = `<a href="magnet:?xt=urn:btih:BF338271137064DF54C2B267956B3BD848DD3421&dn=x">m</a>
    <div style="display:none" id="files"><table><tr><td>Name</td><td>Size</td></tr>
    <tr class="torrent">
      <td>Inside.Out.2.2024.1080p.BluRay.x264-FHC.mkv</td>
      <td>9.76 GB</td>
    </tr></table></div>`
  expect(detail(page)).toEqual({ hash: 'bf338271137064df54c2b267956b3bd848dd3421', files: [{ name: 'Inside.Out.2.2024.1080p.BluRay.x264-FHC.mkv', size: Math.round(9.76 * 2 ** 30) }] })
  expect(detail('<div id="files"><table></table></div>').hash).toBeUndefined()
})

test('a .torrent gives the info hash and the film file; a pack gives none', () => {
  const enc = (s: string) => `${Buffer.byteLength(s)}:${s}`
  const info = `d5:filesld6:lengthi9000e4:pathl${enc('Coco.2017.1080p.mkv')}eed6:lengthi10e4:pathl${enc('info.txt')}eee4:name${enc('كوكو')}12:piece lengthi16384ee`
  const torrent = Buffer.from(`d8:announce${enc('http://t/a')}4:info${info}e`)
  const t = readTorrent(torrent)
  expect(t?.hash).toBe(createHash('sha1').update(Buffer.from(info)).digest('hex'))
  expect(filmFile(t!.files)).toEqual({ file: 0, filename: 'Coco.2017.1080p.mkv', size: 9000 })
  expect(filmFile([{ name: 'a.mkv', size: 9000 }, { name: 'b.mkv', size: 8000 }])).toBeNull()
  expect(readTorrent(Buffer.from('<html>تجاوزت الحد المسموح</html>'))).toBeNull()
})

test('a dubbed upload is matched to its TMDB film by its names and year, or to none', () => {
  expect(uploadNames('حكاية لعبة ٥ [2026][مدبلج فصحى+مصرية+سعودية][DSNP][1080p] | Toy Story 5')).toEqual({ names: ['Toy Story 5', 'حكاية لعبة 5'], year: '2026' })
  expect(uploadNames('خلطة بيطة بالصلصة — راتاتوي (2007) — BluRay 1080p مدبلج بالعامية المصرية + الإنجليزية Ratatouille')).toEqual({ names: ['خلطة بيطة بالصلصة', 'راتاتوي'], year: '2007' })
  expect(uploadNames('Finding Nemo 2003 2160p DSNP WEB DL DDP5 1 Atmos DV H 265').names).toEqual(['Finding Nemo'])
  const film = (id: number, title: string, year: string, animated = true) => ({ id, title, release_date: `${year}-06-01`, genre_ids: animated ? [16] : [18] })
  // The exact title wins over TMDB's first answer; a one-word name takes nothing less.
  expect(pickFilm([film(1, "WALL·E's Treasures & Trinkets", '2008'), film(2, 'WALL·E', '2008')], 'WALL E', '2008')).toBe(2)
  expect(pickFilm([film(3, 'Sword Art Online: Aria of a Starless Night', '2021')], 'Night', '2021')).toBe(0)
  // A translated name takes the first animated film of its year; live action and other years never.
  expect(pickFilm([film(4, 'Ice Age: Collision Course', '2016')], 'العصر الجليدي', '2016')).toBe(4)
  expect(pickFilm([film(5, 'Night', '2024'), film(6, 'Night', '2021', false)], 'Night', '2021')).toBe(0)
})

test("a show's file is its episode only when named for the show; an upload names its seasons", () => {
  const bluey = ['بلوي', 'Bluey']
  expect(episodeOf('Bluey.S01/Bluey.S01E05.1080p.DSNP.WEB-DL.mkv', bluey)).toEqual([1, 5])
  expect(episodeOf('Bluey.2018.S02E10.mkv', bluey)).toEqual([2, 10])
  expect(episodeOf('S03E01.mp4', bluey)).toEqual([3, 1])
  expect(episodeOf('Bluey.Compilations.S01E01.Outdoor.Adventures.mkv', bluey)).toBeNull()
  expect(episodeOf('Bluey.S01E05.nfo', bluey)).toBeNull()
  expect(episodeOf('تيمون وبومبا الجزء 3 الحلقة 05.mp4', ['تيمون وبومبا'])).toEqual([3, 5])
  expect(searchRows(`<div class="file-item"><a href="index.php?page=torrent-details&amp;id=69399"><span class="tor_free_link">[استعادة] بلوي [مدبلج] Bluey 2018 S01 02 03 </span></a><a href="download.php?id=69399&amp;f=b.torrent">`)[0].title).toBe('[استعادة] بلوي [مدبلج] Bluey 2018 S01 02 03')
  expect(namesSeason('[استعادة] بلوي [مدبلج][مترجم] Bluey.2018.S01-02-03.1080p', 3)).toBe(true)
  expect(namesSeason('[استعادة] بلوي [مدبلج][مترجم] Bluey.2018.S01-02-03.1080p', 4)).toBe(false)
  expect(namesSeason('بلوي [م2][مدبلج][1080p]', 2)).toBe(true)
  expect(namesSeason('Bluey [كامل][مدبلج][1080p]', 4)).toBe(true)
})
