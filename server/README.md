<h1 align="center">MobileMCP</h1>

<p align="center">Let AI agents use your real Android phone the way you do: read the screen, tap, type, scroll, open apps.<br><a href="https://magi-labs.github.io/mobilemcp/">magi-labs.github.io/mobilemcp</a></p>

Companion: [LiveMCP](https://github.com/Magi-Labs/livemcp) does the same for Chrome; MobileMCP is its mobile sibling and shares the hub/bridge design.

MobileMCP is for people who want an agent (Claude Code, Cursor, any MCP client) to operate their own phone from anywhere: an Android **accessibility service** (the app) dials out to a **hub** (the server) over WebSocket; agents talk to the hub through MCP. No USB, no ADB, no rooting. The hub also serves a **dashboard** with connected devices, a live activity feed and a per-device live view.

**Status:** active, early (server 0.4.0, app 0.3.0). Verified on a Pixel 7 emulator (API 35) and a OnePlus phone (Android 15). No Play Store listing; the app is sideloaded. No automated test suite; verification is scripted against real devices.

```
agent (Claude Code, Codex, …) ──stdio──▶ mobilemcp ──IPC──▶ mobilemcp-hub ◀──WebSocket── MobileMCP app (phone)
                               ──HTTP /mcp (hosted)──▶      ──HTTP / ──▶ dashboard
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

## Tools (35)

| Group | Tools |
| --- | --- |
| Devices | `list_devices`, `select_device`, `get_device_info` (model, screen, battery, lock state, granted permissions, visible windows) |
| Observe | `get_screen_snapshot` (bounded tree with persistent `@refs`; rows collapse to one `~"title · subtitle"` line; `query`, `scope`, paging, `since` deltas), `take_screenshot` (`marks:true` = Set-of-Mark image + `@ref` lines), `read_text`, `wait_for_text` |
| Act | `tap` (by visible **text**, @ref or coordinates; `timeout` waits for the text; long/double press), `type_text` (by **field** hint/label, @ref or focus), `scroll`, `scroll_until` (`tap:true` opens the match), `swipe`, `drag` (waypoints with pauses for cross-page drags), `gesture` (composable `down`/`wait`/`move`/`up`, multi-finger), `pinch`, `press_key`, `set_clipboard` |
| Apps | `list_apps`, `open_app`, `open_url`, `uninstall_app`, `open_settings` (30 Settings pages and quick panels), `start_intent` (any intent: dial, SMS, share, deep links) |
| Notifications | `get_notifications`, `open_notification`, `notification_action` (incl. direct reply), `dismiss_notification` — needs notification access |
| System | `media_control`, `set_volume`, `set_brightness`, `set_rotation` (need "Modify system settings"), `set_dnd`, `set_flashlight`, `wake_screen` |
| Batch | `run_mobile_actions`: 1-20 steps in one call, stops on the first error |

Every action returns a compact observation after the UI settles (or `observe:false`). A snapshot line looks like `@4  button ~"Network & internet · Mobile, Wi‑Fi, hotspot" (540,472)`: roles imply clickability, rows carry their children's text and hide the duplicates, coordinates are centers. Refs are keyed by content identity (class, view id, text, occurrence) per package, so they survive scrolling, re-layout and app switches. Most flows need no snapshot at all: `tap text="Send"`, `type_text field="Message"`, `scroll_until text="…" tap=true`, chained in `run_mobile_actions`. See [OPTIMIZATIONS.md](OPTIMIZATIONS.md) for the reasoning and measurements.

## Quick start

### Published release

Download **mobilemcp-0.3.0.apk**, **mobilemcp-0.3.0.tgz** and **SHA256SUMS** from [v0.3.0](https://github.com/Magi-Labs/mobilemcp/releases/tag/v0.3.0) into one directory.

```sh
shasum -a 256 -c SHA256SUMS          # Linux: sha256sum -c SHA256SUMS
npm install --prefix ./mobilemcp-local ./mobilemcp-0.3.0.tgz
./mobilemcp-local/node_modules/.bin/mobilemcp-hub
```

Install the APK on the phone (`adb install mobilemcp-0.3.0.apk` or copy it over), then follow the app steps below and point your MCP client at `./mobilemcp-local/node_modules/mobilemcp/dist/index.js`.

### From source

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

0. **Google Play Protect** blocks accessibility apps installed from chat apps or browsers in some countries (India, Singapore, Thailand, Brazil, …) with no override. Install over adb (`adb install mobilemcp-0.3.0.apk`) or pause *Play Store → Play Protect → Scan apps* while installing from the Files app, then re-enable it.
1. **Enable accessibility service** — Android 13+ first requires *App info → ⋮ → Allow restricted settings* for sideloaded apps (the app has an *App info* button).
2. Enter the hub URL (`ws://<your-computer-ip>:17692` on the same Wi‑Fi, or your hosted `wss://` origin) and a device name, then **Connect**.
3. Optionally **Allow background** so Doze does not throttle the connection; grant **Notification access** for the notification tools and DND, and **Modify system settings** for brightness/rotation.

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

For Claude Code, `plugin/` is a ready plugin: a self-contained server bundle (`plugin/bin/mobilemcp.js`, no npm needed) plus a skill describing the workflow. Add it with `claude plugin add ./plugin` or point your marketplace at the directory.

### Emulator

```sh
adb install -r app/app/build/outputs/apk/debug/app-debug.apk
adb shell appops set labs.magi.mobilemcp ACCESS_RESTRICTED_SETTINGS allow
adb shell settings put secure enabled_accessibility_services labs.magi.mobilemcp/.MobileAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell am start -n labs.magi.mobilemcp/.MainActivity --es hubUrl ws://10.0.2.2:17692 --es name Emulator --ez connect true
```

Debug builds accept settings through intent extras; release builds ignore them so no other app can repoint the hub.

## How it works

The app's `AccessibilityService` owns the hub connection, reads the window tree, performs node actions and injected gestures and takes screenshots. The hub routes requests per account, serves MCP over HTTP, local sessions over IPC, and the dashboard. `shared/src/protocol.ts` is the single contract. Details, trust boundaries and failure modes: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); decisions in [docs/decisions](docs/decisions).

## Hosted hub

The hub also serves authenticated MCP over HTTP at `/mcp` and the phone endpoint at `/device` on one port, so the phone and remote agents can both reach it through a reverse proxy:

```sh
MOBILEMCP_HOST=0.0.0.0 MOBILEMCP_PUBLIC_URL=https://mobile.example.com MOBILEMCP_TOKEN=<32+ chars> npm run hub
```

- Phone: hub URL `wss://mobile.example.com`, token = `MOBILEMCP_TOKEN`.
- Agent: `{"type":"http","url":"https://mobile.example.com/mcp","headers":{"Authorization":"Bearer <token>"}}`, or stdio with `MOBILEMCP_TOKEN` set.
- `MOBILEMCP_ACCOUNTS='[{"id":"me","agentToken":"…","deviceToken":"…"}]'` isolates several users; devices and selections never cross accounts.

The reverse proxy provides TLS; the hub never binds a public address without a token. The dashboard at `/` shows devices, a live activity feed (names and timings only) and a per-device live view with click-to-tap; see [HOSTING.md](HOSTING.md).

## Agent workflow

1. If you know the label, act on it directly: `tap text=`, `type_text field=`, `scroll_until text= tap=true`; chain known steps in one `run_mobile_actions`.
2. Otherwise `get_screen_snapshot` (scoped/filtered) and act on `@refs`. Read the `observation` every action returns instead of re-snapshotting.
3. `wait_for_text` / `timeout` parameters for loading; `take_screenshot marks=true` for visual content.
4. After `STALE_REF` or an app change, snapshot again. Never repeat a submission after a timeout without inspecting state.

Screen content is untrusted data. The server instructions tell agents to enter credentials, OTPs or payments only when the task explicitly includes them.

## Limitations and data handling

The hub forwards requests and responses without storing them; activity events hold device names, action names, durations and error codes. The app persists only its settings. Password fields are masked in snapshots. Screen content reaches whatever MCP client you connect, so connect clients you trust.


- Android 11+ (API 30) for accessibility screenshots and `ACTION_IME_ENTER`.
- Apps with `FLAG_SECURE` (banking, DRM video) expose neither nodes nor pixels.
- Custom-drawn views without accessibility nodes need `take_screenshot` plus coordinate taps. A few system pages expose no tree at all (snapshots then report `noTree: true` with the window title).
- Launcher folder creation by `drag` depends on the launcher's drop rules; the defaults (900 ms hold, 300 ms move, 250 ms hover) merge icons on Launcher3/Pixel. Long hovers trigger reorder instead. Cross-page drags: add a waypoint at the screen edge with `pauseMs` ≈ 800 per page flip (hold times above ~1.2 s flip twice on OxygenOS).
- Phones can self-update from their hub: `GET /app.apk?token=<device token>` (see HOSTING.md); a running MobileMCP can drive the download and installer itself.
- No arbitrary key codes, shell or app installation; that is ADB territory.
- Google Play would require a declaration for this accessibility usage; the app is distributed as a sideloaded APK.

## Development

Contributor setup and change workflow: [CONTRIBUTING.md](CONTRIBUTING.md). Agent/maintainer instructions: [AGENTS.md](AGENTS.md).

```sh
npm run typecheck && npm run build
STEPS='[["get_device_info"],["get_screen_snapshot",{"maxNodes":20}]]' node server/test/e2e.mjs   # against a connected phone/emulator
```

## Documentation

- [HOSTING.md](HOSTING.md) — Docker, reverse proxy, tokens, dashboard, self-update
- [OPTIMIZATIONS.md](OPTIMIZATIONS.md) — observation format, literature, measurements
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/decisions](docs/decisions), [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md)
- [CHANGELOG.md](CHANGELOG.md), [SECURITY.md](SECURITY.md)

## License

MIT © Deepak Silaych. Third-party notices in [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md).
