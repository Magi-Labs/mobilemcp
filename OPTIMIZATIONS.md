# Agent efficiency

What MobileMCP does to keep agents fast and cheap, what the literature suggested, and what was measured. Same spirit as LiveMCP v2: bounded observations, stable references, actions that return state, batches.

## What the papers say (and what transferred)

| Finding | Source | In MobileMCP |
| --- | --- | --- |
| A simplified, ID-annotated GUI representation (HTML-like) made AutoDroid the cheapest agent (~765 tokens/task) while AppAgent's screenshot-heavy loop cost 3×; auto-scrolling scrollables helps decisions | [MobileAgentBench](https://arxiv.org/pdf/2406.08184), [AutoDroid-V2](https://arxiv.org/pdf/2412.18116) | One line per node with a persistent `@ref`; `scroll_until` finds items in long lists in one call |
| Compressing the view hierarchy and assigning unique node IDs shortens prompts | AndroidArena, via the [phone-automation survey](https://arxiv.org/pdf/2504.19838) | Row collapsing, implied clickability, center coordinates, id elision (see measurements) |
| Set-of-Mark screenshots (numbered boxes on interactive elements) plus an element list ground visual decisions | [AndroidWorld / M3A](https://arxiv.org/html/2405.14573), [Universal visual grounding](https://arxiv.org/html/2410.05243) | `take_screenshot marks=true` (and `get_screen_snapshot screenshot=true marks=true`) returns the labelled image with the matching `@ref` lines |
| A small unified action space (click, type, scroll, drag…) generalises best; grounding is the bottleneck | [UI-TARS](https://arxiv.org/pdf/2501.12326), [Mobile-Agent-v3](https://arxiv.org/html/2508.15144v1) | Text-targeted actions (`tap text=`, `type_text field=`) remove the grounding step entirely when the label is known |
| Step count dominates cost and failure rate | [MobileAgentBench](https://arxiv.org/pdf/2406.08184) | Every action returns a compact post-action observation; `run_mobile_actions` chains text-targeted steps; waits are parameters (`timeout`), not calls |

## Implemented (v0.3)

- **Compact lines.** `@ref role "text" (desc) [flags] (cx,cy)`. Roles imply clickability (no `[clk]` on buttons/links/inputs), ids only on unlabelled nodes, center coordinates instead of rects (`bounds:"full"` on demand), activity short names, `keyboard` only when shown.
- **Row collapsing.** A clickable row carrying a derived `~"title · subtitle"` label hides its plain-text children (`verbose:true` restores them). Interactive-only and default views converge.
- **Text targets.** `tap text=` (exact › prefix › substring; interactive first; ambiguity lists refs; `timeout` waits for the text), `type_text field=` (waits for the field), `scroll_until … tap=true`.
- **Set-of-Mark screenshots** with the `@ref` lines in the same response.
- **Batches with text targets**, so a known flow is one call (open app → tap → type → wait → scroll-and-tap).
- **Hint fix.** Fields that report their hint as text (WhatsApp composer) no longer get it prepended on append.
- Already in v0.2: persistent refs per package, `since` deltas, settle-after-react observations, `noTree` reporting, `drag` waypoints and the composable `gesture` language.

## Measurement

Pixel 7 emulator, API 35, same screens, default options, serialized response size of `get_screen_snapshot`:

| Screen | v0.2 | v0.3 | Change |
| --- | --- | --- | --- |
| Settings home | 2,476 chars / 31 nodes | 982 chars / 12 nodes | −60% |
| Settings › Network & internet | 1,814 chars / 27 nodes | 727 chars / 11 nodes | −60% |
| Settings home, `interactiveOnly` | 1,217 chars | 982 chars | −19% (now equal to default) |

Step count, Settings flow "search for bluetooth, then open About": v0.2 needed snapshot → tap → snapshot → type → wait → back → scroll ×3 → snapshot → tap (≥ 10 calls); v0.3 runs it as **one** `run_mobile_actions` call with text targets (9 steps, 14 s wall-clock, mostly UI waits).

Not measured: model token counts (depends on the client), success rates on a benchmark, or latency over the hosted path beyond the earlier 0.3–2.6 s per call on 5G.

## Not done (candidates)

- Transition memory (AutoDroid-style "how to get from screen A to B") shared across sessions.
- Automatic scroll-and-collect for long lists (one call returning all rows).
- A `find` tool returning best matches for a label without a full snapshot (text targets cover most of this).
- Screenshot diffing / change-only images.

## Background read queue (preview implementation)

Notification reads, launchable-app lists, execution policy and compact battery/lock status use a separate read queue. They no longer queue behind UI waits. The new compact status does not traverse accessibility windows or inspect the user's foreground app. Mutations remain serialized. Background-only mode blocks all UI operations before dispatch, including whole batches and pending-intent notification actions. On-device local authenticated-hub check: a compact status read completed in 22 ms while a concurrent UI wait lasted 3141 ms; status JSON was 150 bytes. This is one concurrency check, not a general speed benchmark. Hosted verification passed for compact status, launchable-app lists and filtered notification reads with the screen off/locked, plus rejection of app launches. `device.info` switches to compact status and the read queue only in background-only mode for compatibility with older hubs.

## App registry verification

On the Android 15 preview, a temporary probe package was installed, updated, then removed. Reading the persisted registry directly (without requesting reconciliation) confirmed creation, a changed fingerprint and removal from package events. The probe was removed afterward. Four CLI tests cover background notification execution, version invalidation, refusal to override Background only and rejection of an unguarded candidate tap. Android build/policy tests and TypeScript checks passed. Semantic Swiggy address navigation remains a draft requiring foreground learning; it is not a verified CLI/API integration.

### Durable job adapter verification

The standalone stdio adapter completed a real background status job with Background only enabled and fetched one app context without opening it. Seven MCP tools were discovered. Queue fixture checks cover idempotency/conflicts, queued cancellation, notification scope, parameter validation, device pinning, completion and interrupted-worker recovery. Four existing app-route safety tests also pass. This measures correctness, not a throughput or battery benchmark.

### OTP gate verification

Eight extractor/gate tests cover accepted fresh codes, disabled and expired permission, purpose/source/package/challenge scope, freshness/sender/service filtering, ambiguous numbers/codes, email normalization and non-OTP messages. A real stdio adapter fixture discovers ten tools, extracts a synthetic email code and confirms revocation blocks notification access before provider reads. Typecheck/build and the existing four route tests plus queue checks pass. Live phone testing was unavailable because the hosted hub returned HTTP 504; no real OTP was read for testing.

### v0.5.0 phone OTP toggle

OTP is off by default on Android. Dedicated permission queries use the existing notifications.list bridge with otpPermission:true and return only otpAllowed. Tagged OTP notification reads enforce the toggle in the Android dispatcher. The local broker checks the toggle for both email and notification sources and fails closed on absent support or disconnection. Thirteen Python tests, queue invariant checks, three Android execution-policy tests, stdio OTP fixtures, server typecheck/build and release builds passed. No connected USB device was available for this release; on-device toggle interaction remains unverified.
