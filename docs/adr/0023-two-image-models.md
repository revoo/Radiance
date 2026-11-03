# 0023 — Two image models, and photos that follow their captions

Status: accepted · 2026-11-03 · changes SPEC §5.13's single default model

## Context
The first real batch used `flux-1-schnell` with a generic prompt per event ("a meal on a table"). Kristen wrote about salmon and got
eggs, and an arc beat with no visual subject got whatever the model invented. Side by side on real posts, `flux-2-klein-4b` and FLUX 3
Image Klein both looked far more real once the prompt carried the caption. R wants the premium model where a picture isn't disposable.

## Decision
- **Everyday photos use `@cf/black-forest-labs/flux-2-klein-4b`**, still on the free neuron allocation.
- **High-value pictures use `black-forest-labs/flux-3-image-klein`:** photos of a recurring entity, arc beats, posts of importance 4 or
  more, and any avatar that has no approved file. It's a Cloudflare partner model at about $0.009 an image, paid from AI Gateway
  credits, so no new provider account. At most `premium-per-run` a batch; past that, or if the credits run out, the picture falls back
  to the everyday model rather than waiting.
- **The writer says what the camera sees:** a photo is generated after its post is written, from the post's `gist`, which for photo posts
  is one plain sentence of what's in frame. Given the post's own words, the models painted them across the picture.
- **Prompts say what we want, not what we don't.** Told "no faces" the models blurred faces; told "no text" they kept drawing it. R would
  rather have an odd sign than a fake-looking photo, so the rules are gone from the prompt except "no children", the project's adults-only
  line. This replaces SPEC §5.13's list. The writers see each person's written look, so anyone in the picture can be described to resemble their avatar.

## Consequences
- Normal use is at most about $0.11 a day, and only if every batch hits its limit. Auto top-up stays off.
- Images now also come from Black Forest Labs' delivery host, fetched by the server only: the browser still sees only our origin.
- An entity's seed goes to FLUX 3, which takes one, but neither model is given a reference image yet. That's the next step if recurring
  things don't match well enough.
