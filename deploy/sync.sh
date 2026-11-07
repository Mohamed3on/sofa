#!/usr/bin/env bash
# Sync the server to the Hetzner box and restart it.
# Run from the repo root:  SERVER=root@<server-ip> ./deploy/sync.sh
#   /opt/sofa/        ← server.ts, api/, lib/, scripts/ (this rsync target)
#   /opt/sofa/.env    ← XTREAM_*, PLAYLIST_TOKEN, EPG_URLS, TYPESAFE_API_KEY; never synced
#   /opt/sofa/.cache/ ← the box's own data (feed quality, learned competitions, guide matches,
#                       library, …), written by the server and its jobs; never synced
set -euo pipefail

: "${SERVER:?set SERVER=root@<ip>}"
APP_DIR=/opt/sofa

rsync -az --delete server.ts api lib scripts "$SERVER:$APP_DIR/"
rsync -a deploy/*.service deploy/*.timer "$SERVER:/etc/systemd/system/"
ssh "$SERVER" "chown -R sofa:sofa $APP_DIR && systemctl daemon-reload && systemctl enable --now sofa-learn.timer sofa-library.timer && systemctl enable sofa && systemctl restart sofa"
