# Sofa

Sofa cuts your Xtream Codes IPTV subscription down to what you actually watch. It has two parts: a small server you host, which does the trimming, ranking and guide work, and a Fire TV app that plays the result. The server's playlist also works on its own in any IPTV player, so the app is optional.

## What you get

**In any IPTV player** (TiviMate, VLC, …), from the server alone

- Only the channels you choose, in groups you name, instead of the provider's thousands.
- A 🏆 group per league and cup (Premier League, Champions League, …), learned each day from the guide: every channel showing that competition, from any country.
- The best copy of each channel first, ranked by the resolution, frame rate and sound it really delivers (measured, not the provider's labels), with one spare. Lower-quality duplicates and empty match slots are left out.
- A fuller guide: copies of a channel share its programmes, and match slots show the match on them.
- 4K films and UFC replays as on-demand entries.

**In the Fire TV app** (Fire OS 8 or later, remote only), everything above plus

- Live now: the matches on right now, one press away.
- Channel groups, favourites, recent channels, and multi-view of up to 3 channels.
- When a feed stalls or fails, it switches to a spare by itself; a dot shows how the stream is doing and why.
- Switches the TV to the video's frame rate, and shows what plays as badges (4K, Dolby Vision, Atmos).
- Asks "Still watching?" after a while, so a forgotten TV doesn't hold your account's connection.
- Installs once over adb, then updates itself.

**Movies & Shows in the app** (needs `TMDB_TOKEN`; without it, only the provider's own groups)

- Rows for your Watchlist, trending, new releases, genres and languages; title pages with plot, cast, trailer and episodes; voice search.
- IMDb and Letterboxd scores (needs `MDBLIST_KEY`), and rows ranked the [TrueScore](https://github.com/Mohamed3on/truescore) way: by how many people loved a title against how many hated it, not by its average.
- With TrueScore running on the same server, its own rows (recent and all-time) and its scores on the films it knows (set `LETTERBOXD_HISTOGRAMS` to its store; without it, those two rows just don't show).
- Plays the best copy from your provider, Real-Debrid or TorBox (needs `REAL_DEBRID_TOKEN` or `TORBOX_TOKEN`).
- Up next and resume, Skip intro, English subtitles on by default.
- Watched marks from Trakt and Letterboxd, and what you finish written back to Trakt (needs `TRAKT_CLIENT_ID`, `LETTERBOXD_USER`).

**Not included:** catch-up, recordings and match reminders. The app runs on Fire TV only, and it's built around a one-stream account.

**To run your own, follow [docs/SETUP.md](docs/SETUP.md).** Every setting is listed in [.env.example](.env.example). The channel choices built in here are personal (which sports, regions, languages and leagues), so choose your own: the guide shows where. The rest of this README covers how the original deployment runs.

## Hosting

The app and its API — the trimmed playlist (`/api/playlist?t=…`) and EPG (`/api/epg?t=…`) for TiviMate — run on a Hetzner box shared with another app (`root@<server-ip>`; GitHub secret `SERVER_HOST`). `server.ts` runs under systemd (`deploy/sofa.service`, `127.0.0.1:8790`), and the Cloudflare Tunnel `sofa` (`cloudflared.service`) routes the hostname to it. Secrets live in `/opt/sofa/.env` (`XTREAM_HOST/USER/PASS`, `PLAYLIST_TOKEN`, `EPG_URLS`, `TYPESAFE_API_KEY`).

The box owns its data in `/opt/sofa/.cache/` (JSON, written atomically); the server reads a file again when it changes, so nothing needs a restart:

- `scripts/match-epg-channels.ts`, run by hand on the box after the channel list changes, writes `.cache/epg-matches.json`.
- Feed quality (`.cache/quality.json`) is probed nightly at 02:23 UTC by GitHub Actions (`.github/workflows/probe-quality.yml`, secrets `XTREAM_HOST/USER/PASS` + `HETZNER_SSH_KEY` + `SERVER_HOST`; run it by hand from the Actions tab), because the provider refuses streams from the box's IP. It runs `SERVER=root@<server-ip> ./deploy/probe-quality.sh 30`, which copies the box's readings, re-probes for 30 minutes and has the box merge what changed, keeping readings the Fire TV app recorded meanwhile (the same command works from a Mac with `.env.local`). Each run first re-checks readings older than 14 days, then chases feeds below their label. It holds the account's one connection slot, so it won't start while someone is watching and stops as soon as someone starts.

A deploy never touches `.cache/`. Every push to `master` deploys (`.github/workflows/deploy.yml`, needs the `HETZNER_SSH_KEY` and `SERVER_HOST` secrets), except pushes that only touch Markdown or `firetv/**`; by hand: `SERVER=root@<server-ip> ./deploy/sync.sh`. Logs: `ssh root@<server-ip> 'journalctl -u sofa -f'`.

## Fire TV app

`firetv/` is a native player app for the Fire TV Stick, sideloaded and self-updating. It reads its own endpoints, each gated by `?t=` against `APP_TOKENS` in the box's `.env` (comma-separated; never `PLAYLIST_TOKEN`):

- `/api/app/channels`: groups, channels, feed families, quality badges and both provider fronts, as JSON.
- `/api/app/guide`: the guide as a gzip SQLite file.
- `/api/app/version`, `/api/app/apk` and `/api/app/dm`: the latest build and its dex metadata (the baseline profile, which the stick compiles at install), served from `/opt/sofa/app/` (deploys never touch it).
- `/api/app/movies`: the on-demand library (4K movies, UFC replays), read from the daily VOD snapshot (`.cache/vod.json`, `lib/provider.ts`), so the app never adds a pull of the 74 MB catalogue.
- `POST /api/app/quality`: what the app measured on a stream it played (resolution, frame rate, codec, Atmos), merged into the quality cache with the probe's rules (`lib/quality.ts`).
- `POST /api/app/events`: tunes with their time to picture, stalls, feed switches and busy / wrong-route / offline problems, appended to `.cache/app-events.jsonl` on the box (rolls over at 20 MB). Read it to see which feeds hold up and how fast zaps are.

`.github/workflows/firetv.yml` runs on pushes touching `firetv/**`. It runs the unit tests, builds a release-signed APK and copies it to the box with a key that can only write `/opt/sofa/app/`: user `sofa-app`, whose `authorized_keys` entry is `restrict,command="rrsync -wo -no-del /opt/sofa/app"`. Secrets: `ANDROID_KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `APP_TOKEN`, `APP_SERVER_URL`, `APP_DEPLOY_SSH_KEY` and `SERVER_HOST`. The APK carries the token, so it is never uploaded as a GitHub artifact. Local builds read the same values from `~/.gradle/gradle.properties` (`IPTV_KEYSTORE_FILE`, `IPTV_KEYSTORE_PASSWORD`, `IPTV_KEY_ALIAS`, `IPTV_KEY_PASSWORD`, `IPTV_APP_TOKEN`, `IPTV_SERVER_URL`). Keep an offline backup of the keystore: without it, the next update needs an uninstall and a fresh adb install.

To rotate the app token:

1. Add the new token to `APP_TOKENS` (`old,new`) and restart `sofa`.
2. Set the `APP_TOKEN` secret to the new token and push a `firetv/` change.
3. Once the stick has self-updated, remove the old token from `APP_TOKENS` and restart again.

First install: on the stick, enable Settings → My Fire TV → Developer options → ADB debugging. Then run `adb connect <stick-ip>` and `cd firetv && ./gradlew installRelease`. The first install must be the release build, or the first self-update fails on a signature mismatch. Grant the app "Install unknown apps" when it first offers an update.

**Baseline profile.** Release builds ship one (`firetv/app/src/main/generated/baselineProfiles/`), so the hot paths (the channel list, zaps, the panels) run compiled from the first frame after an update instead of warming up in the JIT. CI turns it into a dex metadata file (`firetv/baselineprofile/dex-metadata.sh`) that the self-update installs as `base.dm` beside the APK; a missing or failed one never blocks an update. Regenerate it after big UI changes with `firetv/baselineprofile/generate.sh` (needs `bun`; Gradle boots its own headless API 34 emulator, so a connected stick is never used) and commit the result. The profiling builds talk to `firetv/baselineprofile/fixture-server.ts`, a local copy of the channels and guide whose provider route plays a generated test clip (needs `ffmpeg`): the emulator plays, so playback, health and the info view are profiled, but never takes the account's stream slot or writes to the event log.

## Tech stack

- Server: [Bun](https://bun.sh) and TypeScript, no runtime dependencies. A file's folder says what it is:
  - `server.ts`: the HTTP edge, every route in one table with its token gate, cache lifetime and what it invalidates.
  - Routes: `api/<name>.ts` holds the handlers for `/api/<name>` and `/api/<name>/*`; they return data and leave gating, errors and caching to the table.
  - Libraries: `lib/<name>.ts`, with its tests beside it in `lib/<name>.test.ts`; `scripts/<name>.ts` are the jobs (the box's timers, the quality probe).
  - Data: test fixtures in `lib/fixtures/`; what the server and its jobs write lives in `.cache/`, never in git.
- Fire TV app: Kotlin, Jetpack Compose for TV and Media3/ExoPlayer (`firetv/`).
- Logo lab: `bun run lab` tunes the app's mark; `lab/sofa/export.ts` writes its drawables.
