import { Activity, Gauge, Hourglass, Zap } from "lucide-react";
import { usePacer } from "../api/queries.ts";
import type { PacerStep } from "../api/types.ts";
import { Card, Empty } from "../ui/kit.tsx";

function Meter({ label, value, limit, icon }: { label: string; value: number; limit: number; icon: React.ReactNode }) {
  const pct = Math.min(100, (value / limit) * 100);
  const hot = value >= limit;
  return (
    <Card className="p-4">
      <div className="flex items-center justify-between text-sm font-semibold text-muted">
        <span>{label}</span>
        <span className="text-faint">{icon}</span>
      </div>
      <p className="mt-2 text-3xl font-extrabold tabular-nums tracking-tight">
        {value}
        <span className="text-base font-semibold text-faint"> / {limit}</span>
      </p>
      <div className="mt-3 h-2 overflow-hidden rounded-full bg-panel-2">
        <div className={`h-full rounded-full transition-all duration-500 ${hot ? "bg-bad" : "bg-brand"}`} style={{ width: `${pct}%` }} />
      </div>
    </Card>
  );
}

const LANES: { state: PacerStep["state"]; label: string }[] = [
  { state: "PLANNED", label: "Planned" },
  { state: "HELD", label: "Held" },
  { state: "RELEASED", label: "Released" },
  { state: "DROPPED", label: "Dropped" },
  { state: "BACKGROUND", label: "Left to air later" },
];
const WINDOW = 5 * 60_000;

/** The queue over the last five minutes, one row per state: a dot for a reaction, a square for a comment. */
export function QueueTimeline({ steps, now }: { steps: PacerStep[]; now: number }) {
  const w = 640, left = 116, row = 26;
  const x = (at: number) => left + ((at - (now - WINDOW)) / WINDOW) * (w - left - 34);
  const recent = steps.filter((s) => s.at >= now - WINDOW);
  return (
    <Card className="p-4">
      <h3 className="text-sm font-bold">The queue, last five minutes</h3>
      <svg viewBox={`0 0 ${w} ${row * LANES.length + 18}`} className="mt-2 block h-auto w-full" role="img" aria-label={`Pacer queue: ${LANES.map((l) => `${recent.filter((s) => s.state === l.state).length} ${l.label.toLowerCase()}`).join(", ")}`}>
        {LANES.map((l, i) => {
          const y = row * i + row / 2;
          const inLane = recent.filter((s) => s.state === l.state);
          return (
            <g key={l.state}>
              <text x={0} y={y + 4} fontSize={11} fill="var(--muted)">{l.label}</text>
              <line x1={left} x2={w - 34} y1={y} y2={y} stroke="var(--line)" />
              <text x={w} y={y + 4} fontSize={11} textAnchor="end" fill="var(--ink)" data-testid={`lane-${l.state}`}>{inLane.length}</text>
              {inLane.map((s, n) => {
                const title = <title>{`${l.label}: ${s.kind.toLowerCase()}, ${Math.round((now - s.at) / 1000)} s ago`}</title>;
                return s.kind === "COMMENT" ? (
                  <rect key={n} x={x(s.at) - 4} y={y - 4} width={8} height={8} rx={2} fill="var(--viz-pos)" stroke="var(--panel)" strokeWidth={1.5}>{title}</rect>
                ) : (
                  <circle key={n} cx={x(s.at)} cy={y} r={3.5} fill="var(--viz-pos)" stroke="var(--panel)" strokeWidth={1.5}>{title}</circle>
                );
              })}
            </g>
          );
        })}
        <text x={left} y={row * LANES.length + 14} fontSize={10} fill="var(--faint)">5 min ago</text>
        <text x={w - 34} y={row * LANES.length + 14} fontSize={10} fill="var(--faint)" textAnchor="end">now</text>
      </svg>
      <p className="mt-1 flex gap-4 text-xs text-muted">
        <span className="flex items-center gap-1.5"><span className="size-2.5 rounded-full bg-[var(--viz-pos)]" /> Reaction</span>
        <span className="flex items-center gap-1.5"><span className="size-2.5 rounded-sm bg-[var(--viz-pos)]" /> Comment</span>
      </p>
    </Card>
  );
}

function secs(from: number, at: number | null): string {
  return at === null ? "—" : `${((at - from) / 1000).toFixed(1)} s`;
}

/**
 * A basic pacing monitor (SPEC §12 M7): the Pacer's per-minute counts against pacing.yaml's limits,
 * breathing room, hooks, and each response burst's timings, refreshed every two seconds.
 */
export function PacerPage() {
  const m = usePacer();
  const p = m.data?.pacer;
  return (
    <>
      <div className="px-1">
        <h2 className="flex items-center gap-2 text-lg font-bold tracking-tight">
          <Activity className="size-5 text-accent" aria-hidden="true" /> Pacing monitor
        </h2>
        <p className="mt-1 text-sm text-muted">
          How live responses reach you. Targets: first reaction ≤ 6 s, first comment ≤ 25 s, tapering within ~5 minutes.
        </p>
      </div>
      {!p && (
        <Card>
          <Empty icon={<Gauge className="size-6" />} title="No live session">Post something in the feed and come back.</Empty>
        </Card>
      )}
      {p && (
        <>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Meter label="Comments / min" value={p.commentsLastMinute} limit={6} icon={<Zap className="size-4" />} />
            <Meter label="Reactions / min" value={p.reactionsLastMinute} limit={12} icon={<Zap className="size-4" />} />
            <Meter label="Alerts / min" value={p.notificationsLastMinute} limit={4} icon={<Zap className="size-4" />} />
            <Card className="p-4">
              <div className="flex items-center justify-between text-sm font-semibold text-muted">
                <span>Waiting</span>
                <Hourglass className="size-4 text-faint" />
              </div>
              <p className="mt-2 text-3xl font-extrabold tabular-nums">{p.pending}</p>
              <p className="mt-2 text-xs text-muted">
                {p.breathingUntil ? `Breathing room: ${Math.ceil((p.breathingUntil - p.now) / 1000)} s` : "No pause"}
                {m.data?.typing.length ? ` · ${m.data.typing.length} typing` : ""}
              </p>
            </Card>
          </div>
          <QueueTimeline steps={p.steps ?? []} now={p.now} />
          <Card className="overflow-x-auto">
            <table className="w-full min-w-[560px] whitespace-nowrap text-left text-sm">
              <thead className="border-b border-line text-xs uppercase tracking-wider text-muted">
                <tr>
                  <th className="px-4 py-3">Burst</th>
                  <th className="px-3 py-3">1st reaction</th>
                  <th className="px-3 py-3">1st comment</th>
                  <th className="px-3 py-3">Last</th>
                  <th className="px-3 py-3">Reactions</th>
                  <th className="px-3 py-3">Comments</th>
                  <th className="px-3 py-3">Dropped</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {p.bursts.length === 0 && (
                  <tr>
                    <td colSpan={7} className="px-4 py-6 text-center text-muted">No bursts yet this session.</td>
                  </tr>
                )}
                {[...p.bursts].reverse().map((b) => {
                  const late = (b.firstReaction !== null && b.firstReaction - b.start > 6_000) || (b.firstComment !== null && b.firstComment - b.start > 25_000);
                  return (
                    <tr key={b.trigger} className={late ? "bg-accent-soft/40" : ""}>
                      <td className="px-4 py-2.5 font-mono text-xs">{b.trigger.startsWith("hook:") ? "hook" : b.trigger.slice(-8)}</td>
                      <td className="px-3 py-2.5 tabular-nums">{secs(b.start, b.firstReaction)}</td>
                      <td className="px-3 py-2.5 tabular-nums">{secs(b.start, b.firstComment)}</td>
                      <td className="px-3 py-2.5 tabular-nums">{secs(b.start, b.lastRelease)}</td>
                      <td className="px-3 py-2.5 tabular-nums">{b.reactions}</td>
                      <td className="px-3 py-2.5 tabular-nums">{b.comments}</td>
                      <td className="px-3 py-2.5 tabular-nums text-muted">{b.dropped + b.unpaced}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </Card>
          <p className="px-1 text-xs text-faint">
            Max this session: {p.maxCommentsPerMinute} comments / min, {p.maxNotificationsPerMinute} alerts / min. Tune these under Knobs.
          </p>
        </>
      )}
    </>
  );
}
