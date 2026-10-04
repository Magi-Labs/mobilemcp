# MobileMCP architecture

Reviewed: 2026-10-05, server 0.4.0 / app 0.3.0. Scope: one person's Android phones, driven by MCP clients on their computers or by a hosted hub; single-tenant by default, multi-account optional.

## Implemented architecture

```
agent (stdio MCP) ──IPC socket──▶ hub ◀──WebSocket /device── MobileMCP app (AccessibilityService)
agent (HTTP MCP)  ──/mcp + Bearer─▶ hub ──HTTP /, /api/*──▶ dashboard (browser)
```

- **App** (`app/`, Kotlin, API 30+). An `AccessibilityService` owns the hub connection (no foreground service), reads the window tree, performs node actions and injected gestures, takes screenshots, launches apps/intents, and optionally hosts a `NotificationListenerService`. `ScreenObserver` turns the tree into bounded, ref-annotated lines; refs are keyed by content identity per package. Requests run one at a time on a worker thread; each carries a `__deadline` from the hub.
- **Hub** (`server/src/hub.ts`). One process per machine or hosting. Accepts phones on `/device`, MCP clients on `/mcp` (Streamable HTTP, bearer) and local stdio sessions over a Unix socket. A `DeviceRouter` per account holds devices, sessions, pending requests and an activity ring buffer; it emits events for the dashboard. Serves the dashboard page, `/api/status`, `/api/events` (SSE) and `/api/call`, plus `/app.apk` for self-update.
- **Server session** (`server/src/index.ts`, `tools/*`). Registers 35 MCP tools over a `Bridge`; every tool validates with zod and forwards an action to the selected device. Post-action observations come from the app, not the server.
- **Protocol** (`shared/src/protocol.ts`). Action names and per-action execution budgets are the single contract shared by hub, server and app.

Authoritative state: the phone for screen state; the hub for which devices/sessions exist; each session for its selected device. Caches: ref maps on the phone (per package, rebuilt on every collect) and snapshot baselines for `since` deltas (last 6 per package).

## Data and contracts

- Authentication: none on loopback; `MOBILEMCP_TOKEN` or `MOBILEMCP_ACCOUNTS` elsewhere (32+ chars, SHA-256 compared in constant time). Agent tokens authorize `/mcp`, IPC registration and `/api/*`; device tokens authorize `/device` hellos and `/app.apk`.
- Isolation: a `DeviceRouter` per account; device IDs, selections and events never cross accounts.
- Content handling: the hub forwards params/results without inspecting or storing them; activity events hold identities, action names, durations and error codes only. Password fields are masked in snapshots.
- Compatibility: unknown actions fail on the app with `UNKNOWN_ACTION`; the hub accepts legacy hellos; refs are opaque strings and may be invalidated (`STALE_REF`).

## Important flows

- Tool call → `Bridge.request(action, params)` → hub assigns an id and deadline → app executes on its worker → response correlated by id → tool result. Timeouts reject with `BRIDGE_TIMEOUT`; the action may still have completed.
- Observation: `collect()` walks all relevant windows (skipping status bars and the keyboard), assigns refs, collapses labelled rows, pages and truncates; actions wait for the UI to react then settle before observing.
- Device disconnect: pending requests fail with `DEVICE_DISCONNECTED` (unknown outcome); sessions keep their selection and fail fast until the device returns.
- Self-update: dashboard or agent opens `/app.apk?token=` on the phone; the installer replaces the app; the accessibility service re-binds and reconnects (observed ~6 s on OxygenOS 15).

## Failure and recovery

No persistent data on the hub; restart loses in-memory events and selections, and HTTP MCP sessions must re-initialize. The phone app persists only its settings. Play Protect may block sideloading in some regions (documented workaround). Some system windows expose no tree (`noTree`).

## Deployment and observations

Observed 2026-10-05: hub 0.4.0 in Docker behind a TLS reverse proxy, app 0.3.0 on a OnePlus CPH2423 (Android 15) and a Pixel 7 emulator (API 35). Snapshot sizes and step counts in `OPTIMIZATIONS.md`. No automated test suite; verification is the e2e scripts against devices.

## Proposed changes

- Transition memory across sessions (AutoDroid-style), scroll-and-collect for long lists, screenshot diffs (see `OPTIMIZATIONS.md`).
- Per-device scoped tokens instead of one device token per account.

## Tradeoffs and open questions

- Accessibility over ADB: works anywhere the phone has network, no computer required; cannot read FLAG_SECURE screens or send arbitrary key codes (ADR 0001).
- Hub connection inside the accessibility service avoids a foreground notification but depends on OEM battery policies; the app asks for the battery exemption.
