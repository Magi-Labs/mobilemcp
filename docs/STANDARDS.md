# Standards alignment

Review date: 5 October 2026.
Shared guideline revision: `c4ed8b10c1cf75b21d3076e5ec800b257b67ac23`.
Scope: MobileMCP source, documentation and GitHub metadata. This records alignment with maintainer guidelines; it is not a security certification or complete runtime verification.

The shared source is the private Magi Labs standards repository referenced in [AGENTS.md](../AGENTS.md). Everything a public contributor needs is in [CONTRIBUTING.md](../CONTRIBUTING.md), [AGENTS.md](../AGENTS.md) and the documents below; private access is not required to build or contribute.

## Review record

| Area | Evidence / alignment |
| --- | --- |
| Repository identity | Public under Magi-Labs, `main`, concrete description, technology/product topics; homepage points at the README until a product page exists |
| Shared labels | All 10 catalog names, colors and descriptions present; area labels `area:app`, `area:hub`, `area:tools`, `area:launcher` documented in CONTRIBUTING.md; no issues existed, so renaming the earlier custom labels needed no migration |
| Contribution workflow | CONTRIBUTING.md (layout, commands, change expectations, labels), PR template in `.github/` |
| Agent instructions | AGENTS.md with reviewed revision, invariants, commands, verification policy, UI/dependency rules and the one exception |
| Architecture | docs/ARCHITECTURE.md distinguishes implemented, deployed (date, versions) and proposed; ADR 0001 (accessibility driver) and 0002 (dashboard) |
| Contracts | Tool contract lives in `server/src/tools/*` schemas and descriptions; protocol in `shared/src/protocol.ts`; README tool table is a summary, not a second copy |
| UI | Dashboard is one self-contained page on vendored Pico CSS 2.1.1 with its MIT notice; semantic elements, Pico tokens, loading/empty/error states; inspected in a desktop browser only |
| Secrets and distribution | No tokens or personal hosts in tracked files (scan + manual read before the visibility change); `.env.example` and placeholders in docs; release APKs built without a personal default hub URL |
| Security reporting | SECURITY.md describes the boundary and private reporting; GitHub private vulnerability reporting not yet enabled (see limitations) |
| Release documentation | CHANGELOG.md per version; GitHub releases with APK, server tarball and checksums |
| Third-party provenance | docs/THIRD_PARTY.md |

## Current limitations and reconsideration triggers

- **Verification:** typecheck/build and scripted e2e runs against an emulator and one phone; no automated test suite, no accessibility audit of the dashboard, no mobile-viewport inspection of it. Add tests before claiming regression safety.
- **Tenancy:** one device token per account; a dashboard viewer holds the full agent token. Revisit with per-device scoped tokens (ADR 0002 follow-up).
- **Compatibility:** app and hub may run different versions; unknown actions fail cleanly but no compatibility matrix is published.
- **Distribution:** sideload only; Play Protect blocks installs from chat/browser in some regions. Documented, not solved.

## Checks performed

- Read the standards README, policies (repositories, labels, documentation, agents, security, UI, architecture) and templates at the revision above.
- `npm run typecheck`, `npm run build`; dashboard and `/api/*` exercised against the local hub with an emulator; labels compared with `gh label list`.
