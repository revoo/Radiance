# 0007 — SSE over WebSockets

Status: accepted · 2026-10-27 · SPEC §2 P8, §3.3, §7

## Context
The browser needs pushed updates (aired items, typing indicators, meters, notifications). Client →
server traffic is ordinary commands.

## Decision
Server → client uses Server-Sent Events (`GET /api/stream` via Spring MVC's `SseEmitter`, on virtual
threads); client → server uses REST. The SPA uses native `EventSource` and patches TanStack Query
caches from events.

## Consequences
- Plain HTTP: works through proxies, the Vite dev proxy and Cloudflare without extra configuration;
  automatic reconnects.
- One-way only; revisit if client → server streaming is ever needed.
