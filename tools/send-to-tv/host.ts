// Chrome native messaging host for the Send to TV extension: opens a YouTube URL in SmartTube on the TV over adb.
const ADB = `${process.env.HOME}/Library/Android/sdk/platform-tools/adb`;
const TV = '192.168.178.111:5555';

// Chrome keeps stdin open until we reply, so read just the one length-prefixed message.
let buf = new Uint8Array(0);
const reader = Bun.stdin.stream().getReader();
const need = () => (buf.length < 4 ? Infinity : 4 + new DataView(buf.buffer, buf.byteOffset).getUint32(0, true));
while (buf.length < need()) {
  const { value, done } = await reader.read();
  if (done) break;
  buf = new Uint8Array([...buf, ...value]);
}
const msg = JSON.parse(new TextDecoder().decode(buf.subarray(4, need())));

function adb(...args: string[]) {
  const p = Bun.spawnSync([ADB, ...args], { timeout: 8000 });
  return { ok: p.exitCode === 0, out: `${p.stdout}${p.stderr}`.trim() };
}

let reply: { ok: boolean; error?: string };
if (!/^https:\/\/([\w-]+\.)?(youtube\.com|youtu\.be)\/[^']*$/.test(msg.url)) reply = { ok: false, error: 'Not a YouTube URL' };
else {
  adb('connect', TV); // also wakes the TV from standby
  const r = adb('-s', TV, 'shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-d', `'${msg.url}'`, 'org.smarttube.beta');
  reply = r.ok && !/Error/.test(r.out) ? { ok: true } : { ok: false, error: r.out || 'adb failed (is the TV on?)' };
}

const body = new TextEncoder().encode(JSON.stringify(reply));
const head = new Uint8Array(4);
new DataView(head.buffer).setUint32(0, body.length, true);
process.stdout.write(Buffer.concat([head, body]), () => process.exit(0)); // stdin is still open: exit, don't wait on it
