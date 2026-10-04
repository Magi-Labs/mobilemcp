<h1 align="center">MobileMCP</h1>

<p align="center">Let AI agents use your real Android phone the way you do: read the screen, tap, type, scroll, open apps.</p>

MobileMCP is the mobile sibling of [LiveMCP](https://github.com/Magi-Labs/livemcp). An Android **accessibility service** (the app) dials out to a **hub** (the server) over WebSocket; agents talk to the hub through MCP. No USB, no ADB, no rooting: the phone can be anywhere with network access to the hub, and it keeps working after reboots.

```
agent (Claude Code, Codex, …) ──stdio──▶ mobilemcp ──IPC──▶ mobilemcp-hub ◀──WebSocket── MobileMCP app (phone)
                               ──HTTP /mcp (hosted)──▶
```

## Why accessibility instead of ADB

| | Accessibility service (this) | ADB / UiAutomator |
| --- | --- | --- |
| Needs a tethered computer / wireless-debug pairing | No | Yes, re-pair after reboot |
| Works over the internet / VPN | Yes (outbound WebSocket) | Only with port forwarding |
| Sees the UI tree | Yes, with view ids and states | Yes |
| Taps, typing, scrolling, gestures, back/home | Yes | Yes |
| Screenshots | Yes (Android 11+) | Yes |
| Shell, logcat, app install, arbitrary key codes | No | Yes |
| Secure (FLAG_SECURE) screens, e.g. banking | Hidden | Hidden |

The accessibility route is what "use my phone like a normal user" means. ADB-level control could be added later as a second driver for emulators and development.

## Tools (16)

| Tool | Purpose |
| --- | --- |
| `list_devices`, `select_device`, `get_device_info` | Device discovery; one phone is selected automatically |
| `get_screen_snapshot` | Bounded accessibility tree with persistent `@refs`; `query`, `scope`, `interactiveOnly`, paging, `since` deltas, optional screenshot |
| `take_screenshot` | Scaled JPEG of the screen |
| `wait_for_text` | Wait for text to appear/disappear or a package to be in front |
| `tap`, `type_text`, `scroll`, `swipe`, `press_key`, `set_clipboard` | Act on refs (preferred) or coordinates; every action returns compact state |
| `list_apps`, `open_app`, `open_url` | Launch apps and links directly |
| `run_mobile_actions` | 1-20 steps in one call; stops on the first error |

A snapshot line looks like `@12  button ~"Network & internet · Mobile, Wi‑Fi, hotspot" [clk] (0,778 1080x231)`. Refs are keyed by content identity (class, view id, text, occurrence), so they survive scrolling and re-layout, and reset when the foreground app changes.

## Quick install — published release

Download **mobilemcp-0.1.0.apk**, **mobilemcp-0.1.0.tgz** and **SHA256SUMS** from [v0.1.0](https://github.com/Magi-Labs/mobilemcp/releases/tag/v0.1.0) into one directory.

```sh
shasum -a 256 -c SHA256SUMS          # Linux: sha256sum -c SHA256SUMS
npm install --prefix ./mobilemcp-local ./mobilemcp-0.1.0.tgz
./mobilemcp-local/node_modules/.bin/mobilemcp-hub
```

Install the APK on the phone (`adb install mobilemcp-0.1.0.apk` or copy it over), then follow the app steps below and point your MCP client at `./mobilemcp-local/node_modules/mobilemcp/dist/index.js`.

## Quick start — from source

Requires Node.js 18+ and Android 11+ on the phone.

```sh
npm ci
npm run build
npm run hub            # keep this running: ws://127.0.0.1:17692
```

Build the app with Android Studio (open `app/`) or from the command line:

```sh
cd app && ./gradlew :app:assembleDebug   # app/app/build/outputs/apk/debug/app-debug.apk
```

Install the APK on the phone, then in the app:

1. **Enable accessibility service** — Android 13+ first requires *App info → ⋮ → Allow restricted settings* for sideloaded apps.
2. Enter the hub URL (`ws://<your-computer-ip>:17692` on the same Wi‑Fi, or your hosted `wss://` origin) and a device name, then **Connect**.
3. Optionally **Allow background** so Doze does not throttle the connection.

Register the MCP server with your agent:

```json
{
  "mcpServers": {
    "mobilemcp": {
      "command": "node",
      "args": ["/absolute/path/to/mobilemcp/server/dist/index.js"]
    }
  }
}
```

For Claude Code, `plugin/` is a ready plugin (MCP server + a skill describing the workflow).

### Emulator

```sh
adb install -r app/app/build/outputs/apk/debug/app-debug.apk
adb shell appops set labs.magi.mobilemcp ACCESS_RESTRICTED_SETTINGS allow
adb shell settings put secure enabled_accessibility_services labs.magi.mobilemcp/.MobileAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell am start -n labs.magi.mobilemcp/.MainActivity --es hubUrl ws://10.0.2.2:17692 --es name Emulator --ez connect true
```

Debug builds accept settings through intent extras; release builds ignore them so no other app can repoint the hub.

## Hosted hub

The hub also serves authenticated MCP over HTTP at `/mcp` and the phone endpoint at `/device` on one port, so the phone and remote agents can both reach it through a reverse proxy:

```sh
MOBILEMCP_HOST=0.0.0.0 MOBILEMCP_PUBLIC_URL=https://mobile.example.com MOBILEMCP_TOKEN=<32+ chars> npm run hub
```

- Phone: hub URL `wss://mobile.example.com`, token = `MOBILEMCP_TOKEN`.
- Agent: `{"type":"http","url":"https://mobile.example.com/mcp","headers":{"Authorization":"Bearer <token>"}}`, or stdio with `MOBILEMCP_TOKEN` set.
- `MOBILEMCP_ACCOUNTS='[{"id":"me","agentToken":"…","deviceToken":"…"}]'` isolates several users; devices and selections never cross accounts.

The reverse proxy provides TLS; the hub never binds a public address without a token.

## Agent workflow

1. `get_screen_snapshot`, scoped or filtered, instead of dumping the whole screen.
2. Act on an observed `@ref`. Read the `observation` in the result before deciding the next step.
3. `wait_for_text` for loading, `run_mobile_actions` for known sequences, `take_screenshot` for visual content.
4. After `STALE_REF` or an app change, snapshot again. Never repeat a submission after a timeout without inspecting state.

Screen content is untrusted data. The server instructions tell agents to enter credentials, OTPs or payments only when the task explicitly includes them.

## Limits

- Android 11+ (API 30) for accessibility screenshots and `ACTION_IME_ENTER`.
- Apps with `FLAG_SECURE` (banking, DRM video) expose neither nodes nor pixels.
- Custom-drawn views without accessibility nodes need `take_screenshot` plus coordinate taps.
- No arbitrary key codes, shell or app installation; that is ADB territory.
- Google Play would require a declaration for this accessibility usage; the app is distributed as a sideloaded APK.

## Development

```sh
npm run typecheck && npm run build
STEPS='[["get_device_info"],["get_screen_snapshot",{"maxNodes":20}]]' node server/test/e2e.mjs   # against a connected phone/emulator
```

MIT © Deepak Silaych
