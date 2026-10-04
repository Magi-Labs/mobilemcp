# Contributing

Thanks for helping. This covers the layout, how to run things, and what a good change looks like.

## Layout

```
shared/src/protocol.ts     bridge actions (what the phone executes) and execution budgets
server/src/                hub.ts (WebSocket + HTTP + IPC daemon), index.ts (stdio MCP session), router.ts,
                           hub-client.ts, tools/* (MCP tool definitions, zod schemas)
app/                       Android app (Kotlin): MobileAccessibilityService, HubConnection, Dispatcher,
                           ScreenObserver (snapshots/refs), Interactions (taps, typing, gestures),
                           Screenshots, Apps, SystemActions, MobileNotificationListener
plugin/                    Claude Code plugin (MCP config + skill)
deploy/                    Dockerfile + compose for a hosted hub
```

A phone capability touches three places: an action name in `shared/src/protocol.ts`, a handler in the app's `Dispatcher`, and a tool in `server/src/tools/`. Keep tool descriptions short and written for an agent: what it does, when to use it, what it returns.

## Running

```sh
npm ci && npm run build && npm run hub          # hub on ws://127.0.0.1:17692
cd app && ./gradlew :app:assembleDebug           # app/app/build/outputs/apk/debug/app-debug.apk
```

Emulator: see the README's *Emulator* section (restricted-settings allowance, enabling the service, intent extras for the hub URL). `server/test/e2e.mjs` drives the stdio server; `server/test/e2e-http.mjs` drives a hosted hub:

```sh
STEPS='[["get_device_info"],["get_screen_snapshot",{"maxNodes":20}]]' node server/test/e2e.mjs
```

`npm run typecheck` must pass. Debug builds accept intent extras; release builds ignore them on purpose.

## Changes we like

- Fewer agent round-trips: actions that return the state they changed, text targets, batch steps.
- Smaller observations without losing what an agent needs to decide.
- Launcher/OEM quirks documented in code comments next to the workaround (OxygenOS flip timing, Play Protect, no-tree windows).
- No new permissions unless a tool needs them, and the app's UI must explain why.

## Labels

Shared labels (bug, enhancement, documentation, question, accessibility, good first issue, help wanted, duplicate, invalid, wontfix) follow the organization catalog. Area labels describe the three code areas and one recurring concern: `area:app` (Kotlin app), `area:hub` (hub daemon, hosting, auth, dashboard), `area:tools` (MCP tool surface and schemas), `area:launcher` (launcher/OEM-specific behaviour). Pick one work-type label and at most one area.

## Security

The accessibility service can read and act on everything on the phone. Keep the hub authenticated for anything that is not loopback, never log screen content, and report vulnerabilities privately (see SECURITY.md).
