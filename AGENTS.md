# Working on MobileMCP

## Shared maintainer guidelines

Authorized maintainers use https://github.com/Magi-Labs/standards.
Reviewed standards revision: c4ed8b10c1cf75b21d3076e5ec800b257b67ac23.
Read relevant policies when the private repo is accessible. The instructions below retain all public/project-specific requirements. If private access is unavailable, follow these local instructions and report the limitation.

## Scope and invariants

- MobileMCP lets an agent use one or more real Android phones through an accessibility service. The phone owns its screen state; the hub owns connection state and account isolation; a stdio or HTTP MCP session owns its device selection.
- Three pieces evolve together and must stay consistent: `shared/src/protocol.ts` (action names, execution budgets), the app's `Dispatcher` (handlers) and `server/src/tools/` (MCP tools and schemas). A tool with no handler, or a handler with no budget, is a bug.
- Non-loopback hubs require a token; devices, sessions and dashboard access of one account never see another's. Keep it that way.
- The hub never stores or logs screen content, params or results; activity events carry identities, action names, durations and error codes only.
- A disconnected device means unknown state, not a completed or failed action. Never replay a mutation after a timeout.
- Release builds ignore configuration intent extras; debug builds accept them for emulator setup.

## Structure and commands

```
shared/src/protocol.ts   bridge actions + budgets
server/src/              hub.ts (WebSocket /device, HTTP /mcp, dashboard + /api, IPC), index.ts (stdio session),
                         router.ts, hub-client.ts, tools/*, dashboard/ (Pico-based page, vendored CSS)
app/                     Kotlin app: MobileAccessibilityService, HubConnection, Dispatcher, ScreenObserver,
                         Interactions, Screenshots, Apps, SystemActions, MobileNotificationListener
plugin/                  Claude Code plugin (self-contained bundle in plugin/bin, built by npm run build)
deploy/                  Dockerfile + compose for a hosted hub
docs/                    ARCHITECTURE.md, decisions/, THIRD_PARTY.md, STANDARDS.md
```

- `npm ci && npm run typecheck && npm run build` must pass before a commit that touches `server/` or `shared/`. The build also regenerates `plugin/bin/mobilemcp.js` (committed, distribution artifact).
- `cd app && ./gradlew :app:assembleDebug` for the app. Release APKs for GitHub are built without `-PdefaultHubUrl`.
- Verification is by driving a device: `STEPS='[["get_device_info"]]' node server/test/e2e.mjs` against the local hub + emulator, or `e2e-http.mjs` against a hosted hub. There is no unit-test suite; say so in PRs rather than implying one ran.
- Record measured observation sizes or step counts in `OPTIMIZATIONS.md` when changing the snapshot format or tool surface.

## UI and dependencies

- Dashboard: one self-contained HTML page using Pico CSS 2.1.1 (vendored at `server/dashboard/vendor` with its MIT notice). Use Pico's semantic elements and tokens; do not add a second UI framework or custom primitives for controls. Keep loading/empty/error states and keyboard operation.
- App: plain Android views (AppCompat), no Compose; keep the single settings screen explanatory because every permission it asks for is sensitive.
- Server: `@modelcontextprotocol/sdk`, `ws`, `zod`; bundled with esbuild. See `docs/THIRD_PARTY.md`.

## Data and deployment

- Never commit tokens, hub URLs of personal deployments, device identifiers or screenshots of real phones. Examples use placeholders.
- Hub upgrades are stateless; the phone app and hub may run different versions, so new bridge actions must fail with a clear error on old apps (the app returns `UNKNOWN_ACTION`).
- The hub can serve the APK at `/app.apk?token=`; a phone can update itself through its own MobileMCP session. Document which build (neutral vs personal default URL) is served where.

## Exceptions

- Labels: in addition to the shared catalog this repository uses `area:app`, `area:hub`, `area:tools`, `area:launcher` (documented in CONTRIBUTING.md) because launcher/OEM quirks and the three code areas are real, recurring boundaries. Reconsider if issues stop clustering that way.
