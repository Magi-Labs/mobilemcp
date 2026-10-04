# 0002: Hub-served dashboard as one Pico CSS page

Date: 2026-10-05
Status: accepted
Implementation: implemented (server 0.4.0)
Deployment: hosted hub, 2026-10-05

## Context

Operators need to see which phones are connected, what agents are doing, and occasionally look at or poke a phone without an agent session. The shared UI guideline asks for an established component library and no homemade primitives.

## Options and evidence

- A React + shadcn app would need a build pipeline, a second toolchain in a Node/Kotlin repo and a static-hosting story.
- One self-contained HTML page with Pico CSS 2.1.1 (the pattern already used for the organization's product pages) is served by the hub itself, needs no build step beyond esbuild's text loader, and works offline from the hub's origin.

## Decision

Serve a single Pico-based page from the hub at `/`, backed by three small endpoints: `/api/status`, `/api/events` (SSE) and `/api/call`. Agent token authorizes it; the same account isolation applies.

## Consequences

Minimal code and no framework drift. The page cannot grow into a complex multi-view app without revisiting this; if that happens, the API already exists for a separate frontend. Activity shows names and timings only, never content.

## Migration and rollback

None; the dashboard is additive. Removing it removes only the page and `/api/*`.

## Follow-up

Per-device scoped tokens so a dashboard viewer need not hold the full agent token.
