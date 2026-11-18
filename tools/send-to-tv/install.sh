#!/bin/sh
# Registers host.ts as the native messaging host for the Send to TV extension (Chrome, macOS).
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
HOSTS="$HOME/Library/Application Support/Google/Chrome/NativeMessagingHosts"
mkdir -p "$HOSTS"
printf '#!/bin/sh\nexec "%s" "%s"\n' "$(command -v bun)" "$DIR/host.ts" > "$HOSTS/send_to_tv.sh"
chmod +x "$HOSTS/send_to_tv.sh"
cat > "$HOSTS/com.mohamed3on.send_to_tv.json" <<JSON
{
  "name": "com.mohamed3on.send_to_tv",
  "description": "Send to TV",
  "path": "$HOSTS/send_to_tv.sh",
  "type": "stdio",
  "allowed_origins": ["chrome-extension://pbjkgnoihklolbkcngfnndcipihfbbnb/"]
}
JSON
echo "Registered. Load $DIR/extension in chrome://extensions (Developer mode → Load unpacked)."
