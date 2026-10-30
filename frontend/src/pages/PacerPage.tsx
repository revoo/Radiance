import { Activity, Gauge, Hourglass, Zap } from "lucide-react";
import { usePacer } from "../api/queries.ts";
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
        <h1 className="flex items-center gap-2 text-2xl font-extrabold tracking-tight">
          <Activity className="size-6 text-accent" aria-hidden="true" /> Pacing monitor
        </h1>
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
            Max this session: {p.maxCommentsPerMinute} comments / min, {p.maxNotificationsPerMinute} alerts / min. Tune these in
            content/rules/pacing.yaml.
          </p>
        </>
      )}
    </>
  );
}
