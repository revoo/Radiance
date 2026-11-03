import type { ReactNode } from "react";
import { Card } from "../ui/kit.tsx";

/** "in 3h" or "3h ago", for world times either side of now. */
export function span(at: number, now: number): string {
  const ms = at - now;
  const abs = Math.abs(ms);
  const text =
    abs < 60_000 ? `${Math.max(1, Math.round(abs / 1000))}s`
    : abs < 3_600_000 ? `${Math.round(abs / 60_000)}m`
    : abs < 48 * 3_600_000 ? `${Math.round(abs / 3_600_000)}h`
    : `${Math.round(abs / (24 * 3_600_000))}d`;
  return ms >= 0 ? `in ${text}` : `${text} ago`;
}

export function usd(n: number): string {
  return n > 0 && n < 0.01 ? `$${n.toFixed(4)}` : `$${n.toFixed(2)}`;
}

export function pct(n: number): string {
  return `${Math.round(n * 100)}%`;
}

export const human = (id: string) => id.replace(/_/g, " ").replace(/-/g, " ");

export function when(at: number): string {
  return new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", hour: "numeric", minute: "2-digit", timeZone: "America/Chicago" }).format(at);
}

export const field =
  "rounded-xl border border-line bg-panel-2 px-3 py-2 text-sm text-ink outline-none transition focus:border-accent focus:ring-2 focus:ring-accent/25";

const TONES = {
  plain: "bg-panel-2 text-muted",
  good: "bg-good/15 text-good",
  bad: "bg-bad/15 text-bad",
  accent: "bg-accent-soft text-accent",
  violet: "bg-violet/15 text-violet",
};

export function Pill({ children, tone = "plain" }: { children: ReactNode; tone?: keyof typeof TONES }) {
  return <span className={`inline-flex items-center gap-1 whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-semibold ${TONES[tone]}`}>{children}</span>;
}

export function Section({ title, hint, action, children }: { title: string; hint?: ReactNode; action?: ReactNode; children: ReactNode }) {
  return (
    <Card className="p-4 sm:p-5">
      <div className="mb-3 flex flex-wrap items-start justify-between gap-2">
        <div>
          <h2 className="text-base font-bold tracking-tight">{title}</h2>
          {hint && <p className="mt-0.5 text-sm text-muted">{hint}</p>}
        </div>
        {action}
      </div>
      {children}
    </Card>
  );
}

export function Tile({ label, value, note }: { label: string; value: ReactNode; note?: ReactNode }) {
  return (
    <Card className="p-4">
      <p className="text-xs font-semibold uppercase tracking-wider text-muted">{label}</p>
      <p className="mt-1 text-2xl font-extrabold tabular-nums tracking-tight">{value}</p>
      {note && <p className="mt-0.5 text-xs text-muted">{note}</p>}
    </Card>
  );
}

export function Loading({ what }: { what: string }) {
  return <p className="px-1 py-8 text-center text-sm text-muted" role="status">Loading {what}…</p>;
}

export function Problem({ error }: { error: unknown }) {
  if (!error) return null;
  return (
    <p role="alert" className="rounded-xl bg-bad/10 px-3 py-2 text-sm font-medium text-bad">
      {error instanceof Error ? error.message : "That didn't work"}
    </p>
  );
}

/** A labelled bar: thin, on a faint track, with the number in ordinary ink beside it. */
export function Bar({ label, value, max, text, title }: { label: ReactNode; value: number; max: number; text: string; title?: string }) {
  const width = max > 0 ? Math.min(100, (value / max) * 100) : 0;
  return (
    <div className="grid grid-cols-[minmax(0,9rem)_1fr_auto] items-center gap-3 py-1 text-sm" title={title ?? `${String(label)}: ${text}`}>
      <span className="truncate text-muted">{label}</span>
      <span className="h-2 overflow-hidden rounded-full bg-panel-2">
        <span className="block h-full rounded-full bg-[var(--viz-pos)]" style={{ width: `${width}%` }} />
      </span>
      <span className="tabular-nums">{text}</span>
    </div>
  );
}

/** Two measures on one scale, per category: what we aim for against what happened. */
export function TargetBars({ mix }: { mix: { key: string; target: number; actual: number }[] }) {
  const total = mix.reduce((n, m) => n + m.actual, 0);
  const sum = mix.reduce((n, m) => n + m.target, 0) || 1;
  const rows = mix.map((m) => ({ key: m.key, target: m.target / sum, actual: total ? m.actual / total : 0, count: m.actual }));
  const max = Math.max(0.01, ...rows.flatMap((r) => [r.target, r.actual]));
  return (
    <div>
      <div className="mb-2 flex gap-4 text-xs text-muted">
        <span className="flex items-center gap-1.5"><span className="size-2.5 rounded-sm bg-[var(--viz-mid)]" /> Target</span>
        <span className="flex items-center gap-1.5"><span className="size-2.5 rounded-sm bg-[var(--viz-pos)]" /> Last 7 days</span>
      </div>
      <div className="space-y-2">
        {rows.map((r) => (
          <div key={r.key} className="grid grid-cols-[minmax(0,7rem)_1fr_auto] items-center gap-3 text-sm" title={`${r.key}: target ${pct(r.target)}, actual ${pct(r.actual)} (${r.count})`}>
            <span className="truncate text-muted">{human(r.key).toLowerCase()}</span>
            <span className="space-y-0.5">
              <span className="block h-1.5 rounded-full bg-[var(--viz-mid)]" style={{ width: `${(r.target / max) * 100}%` }} />
              <span className="block h-1.5 rounded-full bg-[var(--viz-pos)]" style={{ width: `${(r.actual / max) * 100}%` }} />
            </span>
            <span className="w-20 text-right tabular-nums text-xs text-muted">{pct(r.target)} / {pct(r.actual)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

/** One series over days, with its own title; hovering a day says what it was. */
export function Spark({ title, points, min, max }: { title: string; points: { day: string; value: number }[]; min: number; max: number }) {
  const w = 320, h = 72, pad = 4;
  if (points.length === 0) return <p className="text-sm text-muted">{title}: nothing recorded yet. A point is added each night.</p>;
  const x = (i: number) => (points.length === 1 ? w / 2 : pad + (i * (w - 2 * pad)) / (points.length - 1));
  const y = (v: number) => h - pad - ((Math.min(max, Math.max(min, v)) - min) / (max - min || 1)) * (h - 2 * pad);
  const line = points.map((p, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)},${y(p.value).toFixed(1)}`).join(" ");
  const last = points[points.length - 1]!;
  return (
    <figure>
      <figcaption className="mb-1 flex items-center justify-between text-xs text-muted">
        <span>{title}</span>
        <span className="tabular-nums text-ink">{last.value.toFixed(max <= 1 ? 2 : 0)}</span>
      </figcaption>
      <svg viewBox={`0 0 ${w} ${h}`} className="h-[72px] w-full" role="img" aria-label={`${title} over ${points.length} days, now ${last.value.toFixed(2)}`}>
        {min < 0 && <line x1={pad} x2={w - pad} y1={y(0)} y2={y(0)} stroke="var(--line)" strokeDasharray="3 3" />}
        {points.length > 1 && <path d={line} fill="none" stroke="var(--viz-pos)" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />}
        {points.map((p, i) => (
          <g key={p.day}>
            <circle cx={x(i)} cy={y(p.value)} r={i === points.length - 1 ? 4 : 2} fill="var(--viz-pos)" stroke="var(--panel)" strokeWidth={i === points.length - 1 ? 2 : 0} />
            <rect x={x(i) - 8} y={0} width={16} height={h} fill="transparent"><title>{`${p.day}: ${p.value.toFixed(2)}`}</title></rect>
          </g>
        ))}
      </svg>
    </figure>
  );
}
