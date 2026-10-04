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
