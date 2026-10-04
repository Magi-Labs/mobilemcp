# 0001: Drive the phone through an accessibility service, not ADB

Date: 2026-10-04
Status: accepted
Implementation: implemented (app v0.1.0 onward)
Deployment: hosted hub + personal phone, 2026-10-04

## Context

Agents need to use a real phone like its owner would, from a computer or a hosted service, over the internet, without the phone being tethered.

## Options and evidence

- **ADB / UiAutomator**: full control (shell, key codes, install) but needs USB or wireless-debugging pairing that resets on reboot, and a host computer near the phone.
- **AccessibilityService**: runs on the phone, dials out over WebSocket, exposes the UI tree with ids/states, performs node actions and injected gestures, takes screenshots (API 30+). Cannot see FLAG_SECURE windows, send arbitrary key codes or install apps. Google Play Protect restricts sideloading such apps in some countries (observed in India, 2026-10-04).

## Decision

Accessibility service as the only driver. ADB-level control may be added later as a second driver for emulators and development.

## Consequences

Phone works from anywhere; no computer needed; one sensitive permission to explain to users. Banking/DRM screens are out of reach by design. Distribution is sideload (hub-served APK, GitHub release), not Play Store.

## Migration and rollback

None; this is the founding choice. A second driver would be additive behind the same protocol.

## Follow-up

Document OEM quirks as they appear (OxygenOS launcher timings, no-tree windows). Reconsider only if a supported, user-authorized alternative to accessibility appears on Android.
