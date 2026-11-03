/** Graph logic with no React in it: where the people go, which lines show, and what color they are. */

import type { GraphEdge } from "./api.ts";

export type Point = { x: number; y: number };
export type Dimension = "affinity" | "trust" | "attraction" | "familiarity" | "respect";

/** One line per pair of people: the two directions averaged, since how they see each other can differ. */
export type Tie = { a: string; b: string; value: number; kinds: string[]; connected: boolean; tags: string[] };

/** A small fixed-seed random, so the same people land in the same places every visit. */
function seeded(seed: number): () => number {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * A force-directed layout (Fruchterman-Reingold): everyone pushes everyone away, ties pull, and the moves shrink until it
 * settles. {@code weight} says how hard a tie pulls, from 0 to 1. Deterministic, and always inside the box.
 */
export function forceLayout(
  ids: string[],
  ties: { a: string; b: string; weight: number }[],
  size: { width: number; height: number },
  options: { iterations?: number; seed?: number; margin?: number } = {},
): Record<string, Point> {
  const { iterations = 240, seed = 7, margin = 28 } = options;
  const random = seeded(seed);
  const sorted = [...ids].sort();
  const w = Math.max(1, size.width - 2 * margin);
  const h = Math.max(1, size.height - 2 * margin);
  const out: Record<string, Point> = {};
  sorted.forEach((id, i) => {
    const angle = (2 * Math.PI * i) / Math.max(1, sorted.length);
    out[id] = { x: w / 2 + (w / 3) * Math.cos(angle) + random() * 4, y: h / 2 + (h / 3) * Math.sin(angle) + random() * 4 };
  });
  if (sorted.length < 2) {
    sorted.forEach((id) => (out[id] = { x: size.width / 2, y: size.height / 2 }));
    return out;
  }
  const k = Math.sqrt((w * h) / sorted.length) * 0.8;
  let temperature = Math.max(w, h) / 8;
  const cool = temperature / (iterations + 1);
  const known = new Set(sorted);
  const live = ties.filter((t) => known.has(t.a) && known.has(t.b) && t.a !== t.b);
  for (let step = 0; step < iterations; step++) {
    const move: Record<string, Point> = {};
    sorted.forEach((id) => (move[id] = { x: 0, y: 0 }));
    for (let i = 0; i < sorted.length; i++) {
      for (let j = i + 1; j < sorted.length; j++) {
        const a = out[sorted[i]!]!, b = out[sorted[j]!]!;
        let dx = a.x - b.x, dy = a.y - b.y;
        let d = Math.hypot(dx, dy);
        if (d < 0.01) {
          dx = random() - 0.5;
          dy = random() - 0.5;
          d = Math.hypot(dx, dy) || 0.01;
        }
        const f = (k * k) / d;
        const mi = move[sorted[i]!]!, mj = move[sorted[j]!]!;
        mi.x += (dx / d) * f;
        mi.y += (dy / d) * f;
        mj.x -= (dx / d) * f;
        mj.y -= (dy / d) * f;
      }
    }
    for (const t of live) {
      const a = out[t.a]!, b = out[t.b]!;
      const dx = a.x - b.x, dy = a.y - b.y;
      const d = Math.hypot(dx, dy) || 0.01;
      const f = ((d * d) / k) * Math.max(0.05, Math.min(1, t.weight));
      const ma = move[t.a]!, mb = move[t.b]!;
      ma.x -= (dx / d) * f;
      ma.y -= (dy / d) * f;
      mb.x += (dx / d) * f;
      mb.y += (dy / d) * f;
    }
    for (const id of sorted) {
      const m = move[id]!, p = out[id]!;
      const d = Math.hypot(m.x, m.y) || 0.01;
      const limit = Math.min(d, temperature);
      p.x = Math.max(0, Math.min(w, p.x + (m.x / d) * limit));
      p.y = Math.max(0, Math.min(h, p.y + (m.y / d) * limit));
    }
    temperature = Math.max(0.5, temperature - cool);
  }
  for (const id of sorted) {
    out[id] = { x: out[id]!.x + margin, y: out[id]!.y + margin };
  }
  return out;
}

/** Merges the directed edges into one tie per pair, valued on {@code dimension}. */
export function tiesFrom(edges: GraphEdge[], dimension: Dimension): Tie[] {
  const pairs = new Map<string, GraphEdge[]>();
  for (const e of edges) {
    const key = e.from < e.to ? `${e.from}|${e.to}` : `${e.to}|${e.from}`;
    pairs.set(key, [...(pairs.get(key) ?? []), e]);
  }
  return [...pairs.entries()].map(([key, list]) => {
    const [a, b] = key.split("|") as [string, string];
    return {
      a,
      b,
      value: list.reduce((n, e) => n + e[dimension], 0) / list.length,
      kinds: [...new Set(list.map((e) => e.kind))],
      connected: list.some((e) => e.connected),
      tags: [...new Set(list.flatMap((e) => e.tags))],
    };
  });
}

/** What the filters leave: a kind, a word in a tag, and only ties at least this strong (either way). */
export function filterTies(ties: Tie[], f: { kind?: string; tag?: string; min?: number; connected?: boolean }): Tie[] {
  const tag = f.tag?.trim().toLowerCase();
  return ties.filter(
    (t) =>
      (!f.kind || t.kinds.includes(f.kind)) &&
      (!tag || t.tags.some((x) => x.toLowerCase().includes(tag))) &&
      (!f.connected || t.connected) &&
      Math.abs(t.value) >= (f.min ?? 0),
  );
}

/** The dimensions that run from -100 to 100; the others run from 0 to 100. */
export function isTwoSided(d: Dimension): boolean {
  return d === "affinity" || d === "respect";
}

/**
 * Warm to cool through a neutral middle (a diverging scale, for the two-sided dimensions) or one hue from faint to full.
 * Colors come from the theme's tokens, so dark mode has its own steps.
 */
export function tieColor(value: number, d: Dimension): string {
  const strength = Math.round(Math.min(100, Math.abs(value)));
  if (!isTwoSided(d)) return `color-mix(in oklab, var(--viz-pos) ${strength}%, var(--viz-mid))`;
  return `color-mix(in oklab, var(${value >= 0 ? "--viz-pos" : "--viz-neg"}) ${strength}%, var(--viz-mid))`;
}

export function tieWidth(value: number): number {
  return 1 + (Math.min(100, Math.abs(value)) / 100) * 2.5;
}
