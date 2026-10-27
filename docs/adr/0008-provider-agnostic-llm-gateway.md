# 0008 — A provider-agnostic LLM gateway without vendor SDKs

Status: accepted · 2026-10-27 · SPEC §2 P5, P10, §3.4, §5.10

## Context
Models change fast, prices differ by an order of magnitude, and R plans to run uncensored open-weight
models. Domain code must not care which model serves a purpose, and every token must be metered.

## Decision
- Domain code calls `LlmGateway` with a *purpose* and a typed output record. Routes map purposes to
  model aliases with ordered fallbacks; aliases carry provider, model id, capabilities, params and
  prices. All of it is configuration.
- Two small HTTP adapters on Spring `RestClient`, one per wire protocol: OpenAI-compatible Chat
  Completions and Anthropic Messages. Plus `FakeLlm` and `ReplayLlm` for tests and offline play.
- No vendor SDKs, Spring AI or LangChain4j. One JSON library (Jackson 3).
- Our code owns output schemas, parsing, validation and repair, budgets, metering, retries, fallbacks
  and the circuit breaker, so guardrails hold whichever model is behind a route.

## Consequences
- Moving a purpose between providers is a config change; a bake-off picks models by quality per dollar.
- We maintain a few JSON shapes per protocol and track provider API changes ourselves.
- A vendor SDK can come back behind the port if a needed feature is SDK-only.
