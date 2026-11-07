# Setting up your own

Sofa has two parts. The server (steps 1–4) turns an Xtream Codes subscription into a trimmed, quality-sorted playlist and guide that works in TiviMate or any IPTV player. The Fire TV app in `firetv/` (step 5, optional) plays from that same server and adds a Movies & Shows library.

## What you need

- An **Xtream Codes** IPTV subscription (host, username, password).
- A **Linux server** (any small VPS; Debian or Ubuntu) you can reach as root over SSH.
- A **domain** served over HTTPS. These steps use a Cloudflare Tunnel; any reverse proxy in front of `127.0.0.1:8790` works.
- On your computer: [Bun](https://bun.sh), `rsync`, `ssh`.
- For the Fire TV app: a Fire TV running Fire OS 8 (Android 11) or later, JDK 17, the Android SDK and `adb`.

## 1. Settings

Copy [`.env.example`](../.env.example) to `.env` and fill in the required block: `XTREAM_HOST/USER/PASS`, a random `PLAYLIST_TOKEN` and, for the app, a different random `APP_TOKENS` (`openssl rand -hex 24` for each). Everything else is optional and documented there.

**Channels: choose your own.** The channel choices built into this repo are one person's tastes: football, tennis and UFC; UK, Spanish, German, Italian, French and Arabic sport; English feeds first, then Arabic; kids channels in a few languages; Russian TV; the leagues they follow. Treat them as an example, not a default to keep. They all live in `lib/curation.ts`:

| What | Where |
| --- | --- |
| Which categories are listed | `KEEP` (one provider's category ids, which only apply on that provider), `AUTO_REGION` and `AUTO_TOPIC` (picked by name on any provider), or `EXTRA_CATEGORIES` in `.env` |
| The groups, their names and order | `B` (a list of all the groups), `CAT_BUCKET`, `bucketForCategory` |
| Which language's feed comes first | `languageRank`, `regionPref` |
| The 🏆 league and cup groups | `COMPS` |
| Which films are listed | `claims4K` in `lib/provider.ts` (4K categories), `ufcReplays` |

Out of the box, on any provider other than the original, the server lists UK, ES, DE, IT, FR and AR football, tennis, UFC and general sport categories, as long as their names start with a region prefix like `UK|`, `UK |` or `UK:`. For anything else, either set `EXTRA_CATEGORIES` (a regex over category names; each match becomes its own group) or edit the rules above. To see your provider's category names:

```sh
curl -s "$XTREAM_HOST/player_api.php?username=$XTREAM_USER&password=$XTREAM_PASS&action=get_live_categories" | jq -r '.[].category_name'
```

To try the result locally before deploying: put the settings in `.env.local` and run `bun install && bun --env-file=.env.local server.ts`, then open `http://127.0.0.1:8790/api/playlist?t=<PLAYLIST_TOKEN>`.

## 2. The server

On the server, as root:

```sh
apt-get update && apt-get install -y curl unzip rsync
curl -fsSL https://bun.sh/install | BUN_INSTALL=/usr/local bash   # bun at /usr/local/bin/bun
useradd --system --home-dir /opt/sofa --shell /usr/sbin/nologin sofa
mkdir -p /opt/sofa/.cache /opt/sofa/app
# paste your settings into /opt/sofa/.env, then:
chmod 600 /opt/sofa/.env
```

From your computer, in the repo:

```sh
bun install
SERVER=root@<server-ip> ./deploy/sync.sh
```

This copies the server to `/opt/sofa`, installs the systemd units in `deploy/` and starts them. Run the same command again after every change. Check it:

```sh
ssh root@<server-ip> 'curl -s "http://127.0.0.1:8790/api/playlist?t=<PLAYLIST_TOKEN>" | head -c 200'
ssh root@<server-ip> 'journalctl -u sofa -f'
```

Two nightly jobs come with it: `sofa-learn.timer` learns which channels show which competitions (the 🏆 groups), and `sofa-library.timer` builds the Movies & Shows library (only with `TMDB_TOKEN`).

## 3. HTTPS

With your domain on Cloudflare: in the dashboard, under Zero Trust → Networks → Tunnels, create a tunnel and give it a public hostname (say `sofa.example.com`) whose service is `http://127.0.0.1:8790`. The dashboard then shows the install command to run on the server; it ends in `cloudflared service install <token>`.

Turn Bot Fight Mode off for the domain (Security → Bots), or players behind a VPN get challenged.

In TiviMate (or any player), add:

- Playlist: `https://sofa.example.com/api/playlist?t=<PLAYLIST_TOKEN>`
- EPG: `https://sofa.example.com/api/epg?t=<PLAYLIST_TOKEN>`

## 4. Feed quality (optional)

Channels sort by the resolution and frame rate each feed really delivers, from `.cache/quality.json` on the server. Until it has readings, they sort by what the provider's names claim. To measure, run this from any machine whose IP your provider streams to (many providers refuse datacenter IPs), with `.env.local` filled in:

```sh
SERVER=root@<server-ip> ./deploy/probe-quality.sh 30
```

It copies the server's readings, probes for 30 minutes and has the server merge what changed. It uses your account's stream connection, so run it when nobody is watching. The Fire TV app also reports what it measures as you watch.

## 5. The Fire TV app (optional)

1. Make a signing key once, and keep a backup: without it, updates need an uninstall.

   ```sh
   keytool -genkeypair -v -keystore ~/sofa-release.jks -alias sofa -keyalg RSA -keysize 4096 -validity 36500
   ```

2. Add to `~/.gradle/gradle.properties`:

   ```properties
   IPTV_KEYSTORE_FILE=/Users/you/iptv-release.jks
   IPTV_KEYSTORE_PASSWORD=…
   IPTV_KEY_ALIAS=sofa
   IPTV_KEY_PASSWORD=…
   IPTV_APP_TOKEN=<one of APP_TOKENS>
   IPTV_SERVER_URL=https://sofa.example.com
   ```

3. On the Fire TV, enable Settings → My Fire TV → Developer options → ADB debugging. Then:

   ```sh
   adb connect <fire-tv-ip>
   cd firetv && ./gradlew installRelease
   ```

   The first install must be the release build, or the first self-update fails on a signature mismatch.

4. **Updates.** The app updates itself from `/opt/sofa/app/` on the server (`app.apk` plus a `version.json` with its `versionCode`, `versionName` and `sha256`). `.github/workflows/firetv.yml` builds and uploads both on every push that touches `firetv/`. To publish by hand instead, run `./gradlew assembleRelease` and copy `app/build/outputs/apk/release/app-release.apk` there as `app.apk`, with a matching `version.json` (see the workflow's "Copy to the box" step).

`firetv/dev.sh` and `firetv/check.sh` install and check builds on the stick at `STICK=<ip>:5555`.

## 6. Deploy from GitHub (optional)

In a fork, `.github/workflows/deploy.yml` deploys the server on every push to `master`, and `probe-quality.yml` probes feed quality nightly. Add the repository secrets they use:

| Secret | For |
| --- | --- |
| `SERVER_HOST` | the server's IP or hostname (all workflows) |
| `HETZNER_SSH_KEY` | a private key for `root@SERVER_HOST` (deploy, probe) |
| `XTREAM_HOST`, `XTREAM_USER`, `XTREAM_PASS` | the probe |
| `ANDROID_KEYSTORE_B64` (`base64 -i ~/sofa-release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `APP_TOKEN`, `APP_SERVER_URL` | the Fire TV build |
| `APP_DEPLOY_SSH_KEY` | a key for user `sofa-app`, which may only write `/opt/sofa/app/` |

The `sofa-app` user, on the server:

```sh
useradd --system --gid sofa --create-home --shell /bin/sh sofa-app
chmod 2775 /opt/sofa/app   # group-writable, so deploys (which chown everything to sofa) keep it writable
mkdir -p ~sofa-app/.ssh && echo 'restrict,command="rrsync -wo -no-del /opt/sofa/app" <public key>' > ~sofa-app/.ssh/authorized_keys
```
