# Changelog

## [0.6.0] – 2026-10-12

- Shizuku UserService integration with a phone-owned privileged-command switch (off by default) and local permission request button.
- Command access status and direct argv execution tools, plus CLI and local agent adapter support. Bounded output/timeouts, no automatic replay and no command job persistence.
- Commands can change system/data/display even in Background only mode; shell privileges depend on the Shizuku backend.
- Eight JVM tests, thirteen Python tests, stdio fixtures and APK builds pass. On-phone Shizuku permission/binder verification remains pending because no phone is connected.


## [0.5.0] – 2026-10-12

- Phone-owned Background only mode, command wake protection and separate background read queue.
- Version-aware app metadata registry refreshed on installs/updates, with guarded CLI shortcuts; app route learning is deferred.
- Durable local read-only jobs and standalone agent MCP adapter.
- Allow OTP reading toggle (off by default), checked by notification and email OTP tools alongside scoped owner permission. Revocation, freshness and ambiguity tests.
- Release APKs use the current local debug signing key. Its certificate differs from the previous release; the main-package APK cannot update prior signed installs. A separate Preview APK updates the preview channel without replacing the original app. No credentials or private hub defaults are embedded.


## [0.4.0] – 2026-10-05 (server only; app stays 0.3.0)

- Hub dashboard at `/`: connected devices, live activity feed (action names, timings, error codes), per-device live view (screenshot with optional @ref marks, click-to-tap, keys, type/tap by text, snapshot tree). One self-contained Pico CSS page served by the hub.
- Hub APIs for it: `GET /api/status`, `GET /api/events` (SSE), `POST /api/call`; agent-token authenticated, per-account.
- Repository aligned with the Magi Labs standards: AGENTS.md, docs/ARCHITECTURE.md, decision records, third-party notices, PR template, `area:` labels.

## [0.3.0] – 2026-10-05

Agent efficiency release. Snapshot responses are ~60% smaller on the same screens and most flows need no snapshot.

- Compact snapshot lines: roles imply clickability, center coordinates, ids only on unlabelled nodes, rows collapse to one `~"title · subtitle"` line (`verbose:true` restores children, `bounds:"full"` restores rects).
- Text targets: `tap text=` with `timeout` (wait-then-tap), `type_text field=` (waits for the field), `scroll_until … tap=true`; batches accept them.
- Set-of-Mark screenshots: `take_screenshot marks=true` returns the labelled image plus the `@ref` lines.
- Fix: fields that report their hint as text no longer get it prepended on append.
- `OPTIMIZATIONS.md` documents the literature and measurements.

## [0.2.2] – 2026-10-04

- `gesture`: composable touch sequences (`down` / `wait` / `move` / `up`, `tap` / `longpress` / `swipe` sugar), multiple concurrent fingers, one continuous touch.
- `drag`: waypoints with per-point pauses (cross-page launcher drags), icon-image grab, launcher-friendly defaults.
- Hub serves the app at `GET /app.apk?token=…` so a phone can update itself.
- Snapshots report `noTree` for windows without an accessibility tree.

## [0.2.0] – 2026-10-04

- 34 tools: `pinch`, double tap, `scroll_until`, `read_text`; notifications (list / open / action with direct reply / dismiss); `open_settings`, `start_intent`, `uninstall_app`; `media_control`, `set_volume`, `set_brightness`, `set_rotation`, `set_dnd`, `set_flashlight`, `wake_screen`.
- App: stops retrying on a rejected token, token sanity checks, setup checklist, App-info and permission buttons, build-time default hub URL.
- Refs keyed per package and by window package; post-action observations wait for new windows.

## [0.1.0] – 2026-10-04

- First release: accessibility-service app, hub (WebSocket `/device`, HTTP `/mcp`, IPC for stdio sessions), 16 tools with persistent `@refs`, `since` deltas, compact post-action observations, batches, Claude Code plugin.
