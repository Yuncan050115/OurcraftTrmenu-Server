# Changelog

## v1.0.5 — 2026-06-29

- WebSocket bridge between the server plugin and client-side menu rendering
  (`Java-WebSocket` 1.5.7), so menus can be driven from outside the tick loop.
- ItemsAdder treated as a soft dependency (`compileOnly`) — the plugin loads and
  works without it, and lights up custom heads when it is present.
- Kotlin coroutines for the socket handling path; JVM toolchain pinned to 21.
- Requires Paper/Purpur 1.21.4+ API.
