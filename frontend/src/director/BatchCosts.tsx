import { Play } from "lucide-react";
import { worldNow } from "../live/store.ts";
import { Button, Card } from "../ui/kit.tsx";
import { type Run, useBatch, useCosts, useRunBatch } from "./api.ts";
import { Bar, human, Loading, Pill, Problem, pct, Section, span, TargetBars, Tile, usd, when } from "./ui.tsx";

function statusTone(s: string) {
  return s === "DONE" ? "good" : s === "FAILED" ? "bad" : s === "RUNNING" ? "violet" : "plain";
}

function RunRow({ r }: { r: Run }) {
  const secs = r.finishedAt ? Math.round((r.finishedAt - r.startedAt) / 1000) : null;
  return (
    <tr>
      <td className="px-4 py-2.5 tabular-nums">{when(r.startedAt)}</td>
      <td className="px-3 py-2.5">{r.kind.toLowerCase()}</td>
      <td className="px-3 py-2.5" title={r.error ?? r.notes.join("\n")}><Pill tone={statusTone(r.status)}>{r.status.toLowerCase()}</Pill></td>
      <td className="px-3 py-2.5 tabular-nums">{secs === null ? "—" : `${secs}s`}</td>
      <td className="px-3 py-2.5 tabular-nums">{r.planned ?? "—"}</td>
      <td className="px-3 py-2.5 tabular-nums">{r.rendered ?? "—"}</td>
      <td className="px-3 py-2.5 tabular-nums">{r.dropped ?? "—"}</td>
      <td className="px-3 py-2.5 tabular-nums">{r.costUsd === null ? "—" : usd(r.costUsd)}</td>
      <td className="px-3 py-2.5 tabular-nums">{r.rows ?? "—"}</td>
    </tr>
  );
}

/** What the nightly batch has done, how the mix is holding up, and what tonight's script has in it. */
export function BatchPanel() {
  const batch = useBatch();
  const run = useRunBatch();
  if (batch.isPending) return <Loading what="the batch" />;
  if (batch.error) return <Problem error={batch.error} />;
  const b = batch.data;
  const now = worldNow();
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted">
          {b.running ? <span className="font-semibold text-accent">A batch is running now.</span> : <>The script reaches <span className="font-semibold text-ink">{span(b.scriptedUntil, now)}</span> ({when(b.scriptedUntil)}).</>}
        </p>
        <Button busy={run.isPending || b.running} onClick={() => run.mutate()}><Play className="size-4" aria-hidden="true" /> Run the batch now</Button>
      </div>
      <Problem error={run.error} />
      <Card className="overflow-x-auto">
        <table className="w-full min-w-[720px] whitespace-nowrap text-left text-sm">
          <thead className="border-b border-line text-xs uppercase tracking-wider text-muted">
            <tr>
              <th className="px-4 py-3">Started</th><th className="px-3 py-3">Kind</th><th className="px-3 py-3">Status</th><th className="px-3 py-3">Took</th>
              <th className="px-3 py-3">Planned</th><th className="px-3 py-3">Written</th><th className="px-3 py-3">Dropped</th><th className="px-3 py-3">Cost</th><th className="px-3 py-3">Rows</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {b.runs.length === 0 && <tr><td colSpan={9} className="px-4 py-6 text-center text-muted">No runs yet.</td></tr>}
            {b.runs.map((r) => <RunRow key={r.id} r={r} />)}
          </tbody>
        </table>
      </Card>
      <div className="grid gap-3 lg:grid-cols-2">
        <Section title="Event mix" hint="Share of aired events."><TargetBars mix={b.eventMix} /></Section>
        <Section title="News regions" hint="Share of aired news posts."><TargetBars mix={b.regions} /></Section>
      </div>
      <Section title="On the script" hint={`${b.upcoming.posts} posts, ${b.upcoming.comments} comments, ${b.upcoming.reactions} reactions and ${b.upcoming.dms} messages haven't aired yet.`}>
        {b.upcoming.events.length === 0 && <p className="text-sm text-muted">Nothing scripted.</p>}
        <ul className="divide-y divide-line text-sm">
          {b.upcoming.events.map((e) => (
            <li key={e.id} className="flex flex-wrap items-baseline gap-x-2 py-1.5">
              <span className="w-16 shrink-0 tabular-nums text-muted">{span(e.at, now)}</span>
              <span className="font-medium">{e.actor?.name ?? "The world"}</span>
              <span className="text-muted">{human(e.type).toLowerCase()}</span>
              <span className="ml-auto tabular-nums text-xs text-muted" title="Importance">★ {e.importance}</span>
            </li>
          ))}
        </ul>
      </Section>
    </div>
  );
}

function Slices({ title, rows }: { title: string; rows: { key: string; usd: number; calls: number }[] }) {
  const max = Math.max(0, ...rows.map((r) => r.usd));
  const byCalls = max === 0;
  const most = Math.max(1, ...rows.map((r) => r.calls));
  return (
    <Section title={title}>
      {rows.length === 0 && <p className="text-sm text-muted">No calls yet.</p>}
      {rows.map((r) => (
        <Bar key={r.key} label={r.key} value={byCalls ? r.calls : r.usd} max={byCalls ? most : max} text={`${usd(r.usd)} · ${r.calls}`} title={`${r.key}: ${usd(r.usd)} over ${r.calls} calls`} />
      ))}
    </Section>
  );
}

const windowName = (id: string) => (id === "usd:daily" ? "Today" : id === "usd:monthly" ? "This month" : id === "usd:batch" ? "This batch" : id);

/** What the writing costs, against the limits. */
export function CostsPanel() {
  const costs = useCosts();
  if (costs.isPending) return <Loading what="costs" />;
  if (costs.error) return <Problem error={costs.error} />;
  const c = costs.data;
  const wall = costs.dataUpdatedAt;
  return (
    <div className="space-y-3">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Tile label="Today" value={usd(c.today)} />
        <Tile label="This month" value={usd(c.month)} note={`${c.calls} calls`} />
        <Tile label="Cache hit rate" value={pct(c.cacheHitRate)} note="of input tokens, this month" />
        <Tile label="Fallback rate" value={pct(c.fallbackRate)} note="calls past the first attempt" />
      </div>
      <Section title="Budget windows">
        <div className="grid gap-x-8 gap-y-1 md:grid-cols-2">
          {c.windows.filter((w) => !w.id.endsWith(":closed")).map((w) => {
            const money = w.id.startsWith("usd:");
            return <Bar key={w.id} label={windowName(w.id)} value={w.used} max={w.limit} text={money ? `${usd(w.used)} / ${usd(w.limit)}` : `${Math.round(w.used)} / ${Math.round(w.limit)}`} title={`${w.id}: resets ${new Date(w.resetsAt).toLocaleString()}`} />;
          })}
        </div>
        {c.windows.filter((w) => w.closedUntil && w.closedUntil > wall).map((w) => (
          <p key={w.id} role="status" className="mt-2 text-sm font-medium text-bad">{w.id} is closed until {new Date(w.closedUntil!).toLocaleTimeString()}.</p>
        ))}
      </Section>
      {c.parked.length > 0 && (
        <Section title="Parked work" hint="Waiting for a budget window to reset. It picks up by itself.">
          <ul className="text-sm">
            {c.parked.map((p) => <li key={p.reason ?? "?"}><span className="font-semibold tabular-nums">{p.jobs}</span> on {p.reason ?? "a window"} · resumes {new Date(p.resumes).toLocaleString()}</li>)}
          </ul>
        </Section>
      )}
      <div className="grid gap-3 lg:grid-cols-2">
        <Slices title="By purpose, this month" rows={c.byPurpose} />
        <Slices title="By model, this month" rows={c.byModel} />
        <Slices title="By provider, this month" rows={c.byProvider} />
        <Slices title="Per batch" rows={c.perBatch} />
      </div>
    </div>
  );
}
