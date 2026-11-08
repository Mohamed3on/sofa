---
title: Out-of-region football channels - Plan
type: feat
date: 2026-11-08
topic: out-of-region-football
artifact_contract: ce-unified-plan/v1
product_contract_source: ce-plan-bootstrap
execution: code
---

# Out-of-region football channels - Plan

## Goal Capsule

- **Objective:** When a football match is on, the owner sees every channel that shows it at a good picture, from any country: in Live now and in the 🏆 groups, on the Fire TV app and in the TiviMate playlist. A channel from outside the preferred regions appears for a match only when it is high quality and at least as good as one of the channels already shown, or when nothing else shows the match.
- **Means:** Keep the provider's 24/7 sports categories from other countries in hidden per-country buckets that surface only under 🏆 groups (the 🌏 Premier League abroad pattern, generalised), find their matches through their guides and the rest of the futbolenlatv family of listings sites, and filter them by picture against the in-region channels (KTD1–KTD5).
- **Product authority:** The owner (sole user). The Product Contract wins on behaviour; KTDs win on mechanism. The six session-settled decisions below are fixed.
- **Execution profile:** Standard. Server units then one app unit, as commits pushed straight to `master` (no PRs, per the repo convention). The server deploys through `deploy.yml`; the app reaches the stick through `firetv/dev.sh` and its own release workflow. Other Claude sessions share the stick: message them before `dev.sh`.
- **Stop conditions:**
  - Stop and report if keeping the abroad categories pushes a guide build on the box past ~1.2 GB peak (box limit 2 GB, MemoryMax=2G) and U1's bounding cannot bring it back.
  - Stop and report if the listings sites for other countries turn out not to share the parsed layout (U2's parser test fails on a saved page) for more than a handful of countries.
- **Who finishes:** A new session (owner's choice), via `ce-work` or by hand, then verifies on the box and the stick.
- **Open blockers:** None.

---

## Product Contract

### Summary

Today the playlist keeps only provider categories from the owner's regions (UK, ES, DE, IT, Arabic, FR) plus one exception, Premier League channels from SG/NZ/HK, which sit in the hidden bucket `🌏 Premier League abroad` and appear only under 🏆 Premier League. This plan keeps the provider's 24/7 sports categories from every other country the same way, learns what they show from their guides and from the TV listings sites for their countries, and lists them for a match only when their picture holds up against the channels already shown.

### Problem Frame

The owner cares about picture quality, not region. A Portuguese, Dutch or Canadian channel often shows a big match in 1080p50 or 4K while the in-region choices are fewer or worse, and the owner never sees it, because curation drops those categories before anything else looks at them.

### Key Decisions

- KD1. **Quality bar: high quality and at least one channel already shown; anything when nothing else shows it.** session-settled: user-directed — an out-of-region channel is listed for a match when it is high quality (1080p or better) AND its picture is at least that of ANY channel already shown for that match; when no in-region channel shows the match, out-of-region channels are listed whatever their quality; rejected: at least the BEST in-region channel, and listing any out-of-region channel that matches the worst in-region one; reason: owner answered "any channel shown for it", then "we only care about high quality channels really unless nothing else shows the match". Governs R3, R5.
- KD2. **Both app and TiviMate.** session-settled: user-directed — rejected: app only; reason: owner answered "in both". Governs R5.
- KD3. **All countries the listings family covers.** session-settled: user-directed — rejected: a shortlist (FR, PT, NL, US first); reason: "all sites idc i care about quality". Governs R1, R2.
- KD4. **Language only breaks ties.** session-settled: user-directed — rejected: filtering or preferring by language; reason: "no lang needed its just a tiebreaker". Governs R4.
- KD5. **Membership from the current guide, no memory.** session-settled: user-approved — 🏆 membership comes from the guide + listings on each build (learner removed 2026-11-08, 0d6f3b6); rejected: a 60-day or 14-day remembered membership; reason: "i only care that on the day of the match or a bit before the list is correct". Governs R2.
- KD6. **Ship to master, no PR.** session-settled: user-directed — rejected: a pull request; reason: repo convention (always commit and push; deploy.yml ships). Governs the Execution profile.

### Requirements

- R1. The provider's 24/7 sports categories from countries outside the preferred regions are kept, in quality tiers only (RAW, 4K/UHD, FHD, HD/HEVC; never SD or mobile), with best feed plus one spare per channel as in-region.
- R2. An out-of-region channel joins a 🏆 group when its guide, or its country's TV listings site, shows a match of that competition from now on (same rule as in-region channels, KD5).
- R3. In Live now, an out-of-region channel is listed for a match only when it is high quality (measured 1080p or better; unmeasured, an FHD/4K/RAW name marker) and its picture is at least that of the worst in-region channel already listed for that match. When a match has no in-region channel, its out-of-region channels are all listed, best first.
- R4. A match's channels stay ordered by picture first (flaky, height, fps, bitrate detail, marker tier) and language only after that; an out-of-region channel sorts with the rest, never after them by region.
- R5. In the TiviMate playlist an out-of-region channel appears only inside 🏆 groups (never a group of its own), and only when it is high quality and its picture is at least that of the worst in-region channel in that 🏆 group, or the group has no in-region channel (TiviMate cannot filter per match, so the group is the unit).
- R6. Two channels with the same name in different countries ("PL: CANAL+ SPORT" and "AT: CANAL+ SPORT") are different channels: never one family, never failing over to each other, never merged by dedupe.
- R7. Out-of-region feeds get measured by the nightly quality probe like in-region feeds (after them in priority), so the picture comparison moves from name markers to measurements.

### Acceptance Examples

- AE1. A Primeira Liga or Champions League match listed by futebolnatv.pt on "Sport TV 1" shows "PT: SPORT TV 1" (a 1080p feed) under that match in Live now, next to in-region channels that are 1080p or worse.
- AE2. A 720p out-of-region channel is not listed for a match that any in-region channel shows, even a 720p one. A 1080p50 out-of-region channel is listed next to in-region 1080p or 720p channels, and not when every in-region channel is 2160p. A 720p out-of-region channel is listed for a match nothing in-region shows.
- AE3. "PL: CANAL+ SPORT ᴿᴬᵂ" and "AT: CANAL+ SPORT ᴿᴬᵂ" appear as two families when both show matches.
- AE4. The TiviMate playlist has no `🌍`/abroad group of its own; abroad channels appear only under 🏆 groups.

### Scope Boundaries

- Out of scope now: out-of-region **event/PPV slots** (NL| SOCCER PPV, PT| DAZN PPV, CA| SOCCER PPV …). Their quality is unverifiable until a match starts and they would add hundreds of rows. Deferred.
- Out of scope now: the Jev 1.14 "does this programme show this match?" judge for lib/tv-listings.ts (probed this session: 49 pairs, 2 real catches code missed, 11 unsure where leaving the guide alone is right, 1 miss, ~$0.0006). Its own small plan; the request path must not wait on Jev.
- Unchanged: in-region curation, the 🌏 Premier League abroad bucket's channels (they fold into the new mechanism or stay as is, U1 decides), Live now's existing behaviour for in-region channels.

### Sources

- Session 2026-11-08: tonight's Atlético vs Barcelona audit, listings-site discovery (lib/tv-listings.ts, b9f5190), learner replacement (0d6f3b6), owner's answers on quality/region.
- Provider category dump (see Appendix A1); futbolenlatv country-site list (Appendix A2).

---

## Planning Contract

### Key Technical Decisions

- KTD1. **Per-country hidden buckets, keyed by the category's country prefix.** Each kept abroad category maps to a bucket like `🌍 PT` (hidden: listed only under 🏆 groups, filtered from the trailing list exactly as `B.plAbroad` is today in `withCompetitionGroups`). The bucket name carries the country so the app's family key (`home|chanKey`, `lib/app-feed.ts` `familyOf`) and the dedupe key stay country-specific (R6). Rationale: today's plAbroad copies have no home group in the feed, so their family falls back to the 🏆 group and differs per group; a per-country home fixes that and is needed anyway for listings matching (KTD3). `buildAppFeed` must take the home from the hidden bucket even though the channel itself is only listed in 🏆 groups (carry `home` through `withCompetitionGroups` copies, e.g. a `home` field on `Channel`, rather than deriving it from listed groups).
- KTD2. **Explicit category allowlist, not auto-include.** An `ABROAD: Record<categoryId, country>` table next to `KEEP` (from Appendix A1, 24/7 sports categories in quality tiers, no PPV), plus a per-category name filter where a category mixes sport with non-sport (reuse the `CAT_ONLY` mechanism). Rationale: deterministic, testable, matches how `KEEP`/`CAT_BUCKET` work; the provider's category names are inconsistent enough that a regex auto-include would let in entertainment and PPV. Revisit only if categories churn.
- KTD3. **Listings sites per country, `home` = the country bucket.** Add the futbolenlatv family sites (Appendix A2) to `SITES` in `lib/tv-listings.ts`, each with its timezone and `home` set to its country's hidden bucket (in-region countries keep their current buckets). `placeListings` already matches by `home|sorted name`, so a site only ever maps to its own country's channels (no cross-country collisions). Fetch stays every 2 h, all sites in parallel with the existing 15 s timeout; a failed site is skipped.
- KTD4. **Quality comparison = the app's `BEST_FIRST` order, plus a high-quality floor.** Live now (U4) drops an abroad channel that is below 1080p (measured `h`, else its marker: q ≥ 4) or whose `BEST_FIRST` position is worse than the worst in-region channel of the same match; with no in-region channel it keeps them all. The server's TiviMate filter (U3) uses the same keys it already sorts 🏆 groups by (`realRes` height, fps, detail, audio, marker q) against the worst in-region row of the group. Rationale: one definition of "better picture" per side, already used for ordering; unmeasured feeds count as their marker (capped at 1080p unless an event slot), as today.
- KTD5. **Feed carries an `abroad` marker.** The app needs to tell in-region from out-of-region for R3; send `abroad: true` (or the country) on feed channels from abroad buckets. Older apps ignore unknown fields (kotlinx `ignoreUnknownKeys = true`).

### High-Level Technical Design

Directional only.

```
provider categories ──► fetchCuratedChannels ──► in-region buckets (unchanged)
                                   └──────────► ABROAD[cat] → hidden bucket "🌍 <CC>"  (KTD1, KTD2)
guide build (curatedGuide) ── EPG for all kept channels + listings: SITES (all countries, home = bucket)  (KTD3)
                     └─► guideCompetitions → upcomingCompetitions (tvg-id → comps)
withCompetitionGroups(channels, upcoming)
   ├─ 🏆 rows: in-region + abroad members; abroad rows below the group's worst in-region row dropped  (R5, U3)
   └─ trailing list: in-region buckets only (abroad buckets never listed on their own)
/api/playlist (TiviMate)  and  /api/app/channels (feed, abroad: true)
Fire TV liveNow: per match, abroad channels worse than the worst in-region one dropped  (R3, U4)
```

### Assumptions

- Out-of-region channels mostly have guide ids the provider's xmltv or myepg covers; where they don't, the listings sites supply their matches (that is enough for KD5's match-day rule).
- The kept abroad categories add on the order of 200–400 feeds before dedupe. U1 measures the real count and the box's guide-build peak; the stop condition covers a blow-up.
- The new country sites share the futbolenlatv page layout (one family, one template). U2 verifies with a saved page per country before relying on it.

### Implementation Constraints

- The provider account has one connection slot: never open streams from the Mac to check abroad feeds while the owner may be watching; the nightly probe measures them (R7).
- Public repo: no server IP, domain or owner name in code, comments, logs or commits.
- Other sessions edit this working tree and use the stick concurrently: stage only your own hunks; message `sofa-*` sessions before `dev.sh`.

### Sequencing

U1 → U2 → U3 (server, one deploy each or together), then U4 (app). U5 (probe) can follow U1 any time.

---

## Implementation Units

### U1. Keep abroad sports categories in per-country hidden buckets

- **Goal:** Abroad 24/7 sports feeds enter curation as hidden per-country buckets, deduped like in-region, with a country-specific family.
- **Requirements:** R1, R6.
- **Dependencies:** none.
- **Files:** `lib/curation.ts` (an `ABROAD` table and buckets next to `KEEP`/`CAT_BUCKET`/`B`, `CAT_ONLY` filters where needed, `COMP_SOURCE`, the trailing-list filter in `withCompetitionGroups`, the `home` carried on 🏆 copies), `lib/app-feed.ts` (`familyOf` uses the carried home), `lib/curation.test.ts`, `lib/app-feed.test.ts`.
- **Approach:** Pick categories from Appendix A1 (24/7, quality tiers, sport only). Map each to `🌍 <CC>`. Reuse the existing dedupe (best feed + spare by `chanKey` within the bucket). Treat abroad buckets like `B.plAbroad` everywhere it is special-cased (hidden from the trailing list, own dedupe prefix), and consider folding plAbroad into the same mechanism. Measure: number of kept abroad feeds, and a guide build's peak memory on the box (`systemctl status sofa` / `MemoryPeak`) after deploy.
- **Test scenarios:** an abroad category's feed lands in its country bucket and never in the trailing list; "PL: CANAL+ SPORT" and "AT: CANAL+ SPORT" get different families (AE3); an SD/mobile abroad feed is dropped; an abroad PPV category is not kept; the feed's groups list has no abroad bucket (AE4).
- **Verification:** `bun test`; on the box, feed size and guide-build peak within the stop condition.

### U2. TV listings sites for every country in the family

- **Goal:** Matches on abroad channels are known from their country's listings site.
- **Requirements:** R2.
- **Dependencies:** U1 (buckets to map to).
- **Files:** `lib/tv-listings.ts` (`SITES`), `lib/tv-listings.test.ts`.
- **Approach:** Add the Appendix A2 sites with their timezones (verify each by comparing a known kick-off's shown time with UTC, as was done for the four current sites; the Arab site turned out to be Asia/Riyadh). Country-specific name aliases go in `siteKeys` only when a real mismatch shows up (as "M+" → Movistar did). Keep date parsing for both `dd/mm/yyyy` and `dd.mm.yyyy`; add other separators if a site uses them.
- **Test scenarios:** a saved row from at least two new sites parses to the right UTC kick-off; a site name maps only to channels of its own country bucket (a PT site's "Canal+ Sport" never maps to a PL channel); a site that errors is skipped without failing the others.
- **Verification:** `bun test`; a throwaway run over tonight's pages (see the session's `sim.ts` approach) prints mapped vs unmapped names per country; mapped names include each country's main sports channels.

### U3. 🏆 groups take abroad channels at in-region quality or better (TiviMate)

- **Goal:** The playlist lists an abroad channel under a 🏆 group only when its picture is at least that of the group's worst in-region channel.
- **Requirements:** R2, R5.
- **Dependencies:** U1.
- **Files:** `lib/curation.ts` (`withCompetitionGroups`), `lib/curation.test.ts`.
- **Approach:** After building a competition's rows, find the worst in-region row by the same keys the group is sorted by; drop abroad rows below 1080p (`realRes` height) or that sort below that row. A group with no in-region rows keeps its abroad rows. Abroad channels still need `upcoming` membership (KD5) to be in a group at all.
- **Test scenarios:** a 720p abroad row is dropped from any group with an in-region row; a 1080p abroad row is kept next to a 720p in-region row and dropped when every in-region row is 2160p (AE2); a group with only abroad rows keeps them, 720p included; an abroad channel with no upcoming match is in no group.
- **Verification:** `bun test`; playlist on the box lists abroad channels only under 🏆 groups (AE4).

### U4. Live now applies the quality bar per match (Fire TV app)

- **Goal:** Live now lists an abroad channel under a match only when it is at least as good as the worst in-region channel of that match.
- **Requirements:** R3, R4.
- **Dependencies:** U1 (the `abroad` field in the feed, KTD5), server deployed.
- **Files:** `firetv/app/src/main/java/com/mohamed3on/iptv/data/Feed.kt` (`Channel.abroad`), `firetv/app/src/main/java/com/mohamed3on/iptv/data/LiveNow.kt` (`liveNow`, `BEST_FIRST`), `firetv/app/src/test/java/com/mohamed3on/iptv/LiveNowTest.kt`.
- **Approach:** In `liveNow`, after a match's channels are sorted by `BEST_FIRST`, drop abroad channels below 1080p (measured `h`, else `q` ≥ 4) or that compare worse than the last in-region one; if there is no in-region channel, keep all. Language stays the last tiebreaker (KD4). Coordinate with other sessions editing `LiveNow.kt` (it changed several times on 2026-11-08).
- **Test scenarios:** AE2 both ways; a match with only abroad channels lists them; an abroad 4K channel sorts first ahead of in-region 1080p; the zap context (Up/Down through a match's channels) includes kept abroad channels and not dropped ones.
- **Verification:** `./gradlew :app:testDebugUnitTest`; on the stick during a live match, the match's list shows abroad channels that pass the bar.

### U5. Probe abroad feeds too

- **Goal:** Abroad feeds get measured pictures, after in-region feeds.
- **Requirements:** R7.
- **Dependencies:** U1.
- **Files:** `scripts/probe-quality.ts`, `lib/curation.test.ts` or a probe test if one exists.
- **Approach:** The probe ranks feeds by their order in `withCompetitionGroups(channels)` (no `upcoming` there since 0d6f3b6). Make sure abroad buckets' feeds are in the probe set and rank after in-region feeds. Respect the one-slot rules already in the probe.
- **Test scenarios:** the probe's ordering puts in-region feeds first and includes abroad feeds.
- **Verification:** next nightly run (GitHub `probe-quality.yml`) records readings for abroad feed ids in `.cache/quality.json`.

---

## Verification Contract

- Server: `bun test` (all files) and `bunx eslint` on changed files; the pre-existing lint errors in untouched files are not this plan's.
- App: `cd firetv && ./gradlew :app:testDebugUnitTest`.
- Box after deploy: feed and playlist build; guide-build peak memory under the stop condition; `/api/playlist` has no abroad group of its own; spot-check one upcoming match per country with a known abroad broadcaster.
- Stick: Live now during a match with an abroad broadcaster (owner or a later session; never probe streams from the Mac while the owner may watch).

## Definition of Done

- R1–R7 hold, AE1–AE4 pass, all tests green.
- The box's guide build stays within the memory stop condition.
- No abandoned-attempt code in the diff; only this work's hunks committed (other sessions share the tree).
- Commits pushed to `master`; server deployed; app installed on the stick after messaging other sessions.

---

## Appendix

### A1. Provider categories outside the preferred regions (2026-11-08 dump, 24/7 sports candidates)

Quality-tier 24/7 sports categories to start from (ids are the home provider's; verify names before use): PT 2122 MEO DESPORTO ᴿᴬᵂ, 1808 DESPORTO ⱽᴵᴾ ᴿᴬᵂ, 1457 DAZN EXCLUSIVE ᴴᴰ/ᴿᴬᵂ, 1025 DESPORTO · NL 124 SPORT HD/4K, 1645 CANAL+ ONLINE SPORT ᴿᴬᵂ, 1647 CANAL+ ONLINE SPORT ᴴᴰ, 1609 TELE2 SPORT ᴿᴬᵂ · BE 530 SPORT ᴴᴰ/ᴿᴬᵂ · CH 1861 MYSPORTS ᴿᴬᵂ, 1714 BLUE SPORT ᴿᴬᵂ, 1862 BLUE SPORT DIRECT ᴿᴬᵂ · AT 1630 CANAL+ ONLINE SPORT ᴿᴬᵂ · PL 1643 CANAL+ ONLINE SPORT ᴿᴬᵂ, 2324 SPORTOWE ᴴᴰ/ᴿᴬᵂ, 1212 SPORT TVN PLAYER ᵁᴴᴰ · US 492 SPORT ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ · CA 1094 TSN SPORT ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ, 585 SPORTS EN, 580 SPORTS FR · BR 1666 ESPN SPORT ᴴᴰ · GR 1220 ΑΘΛΗΤΙΚΑ/SPORTS ⱽᴵᴾ, 2414 NOVA/SPORTS ⱽᴵᴾ · TR 1328 BEIN SPORTS GOLD, 1466 TABII SPORT ᴿᴬᵂ · SE 2348 VIAPLAY ᴿᴬᵂ · DK 1848 VIAPLAY ᴴᴰ · NZ 2341 SKY SPORT ᴿᴬᵂ, 2340 SKY SPORTS ᴴᴰ · CZ 1680 SKYLINK ᴿᴬᵂ · AL 2195/1668/1425 ALBANIA SPORT ᴿᴬᵂ · AFR 1214 CANAL+ VIP HD/4K. Also in-region-prefixed but not kept: FR 557 SPORT HD/4K, 1627 CANAL+ LIVE ᴿᴬᵂ, 1491 EURO SPORT ᴿᴬᵂ, 2119 FRANCE SPORT ⱽᴵᴾ ᴿᴬᵂ; ES 2113 MOVISTAR SPORT ᴿᴬᵂ, 2107 ORANGE SPORTS ᴿᴬᵂ; UK 303 SPORT ᴴᴰ. The full dump came from `get_live_categories` (one small API call; safe while the owner watches).

### A2. Listings sites (futbolenlatv family, from futbolenlatv.es's country list)

France foottv.fr · Portugal futebolnatv.pt · Netherlands voetbalvandaag-tv.nl · Italy calcio-tv.com · Austria fussball-im-tv.at · Switzerland fussball-im-tv.ch · Poland footballtv.pl · Czechia fotbaldnestv.cz · Slovakia futbaldnes.sk · Denmark fodboldidag.dk · Norway fotballtv.com · Sweden fotbollpatvidag.se · Finland jalkapallotv.fi · Ireland live-footballtv.com · USA sportsontvusa.com (EN) and futbolenvivousa.com (ES) · Canada sportsguidetv.com · Brazil futebolaovivobrasil.com · Mexico futbolenvivomexico.com · Argentina futbolenvivoargentina.com and other LatAm futbolenvivo<country>.com sites · Australia gamesontvsports.com · India livesportstv.in · Japan footballtv.jp. Already used: futbolenlatv.es, fussball-im-tv.com, ukfootballontv.co.uk, livefootballtv.info. Belgium has no site in the list (Belgian channels map only through their guides).
