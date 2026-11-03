import { ChevronDown, Lock, Search } from "lucide-react";
import { useState } from "react";
import { worldNow } from "../live/store.ts";
import { Avatar, Card, Empty, Segmented } from "../ui/kit.tsx";
import { type ArcView, useArcEvents, useArcs } from "./api.ts";
import { field, human, Loading, Pill, Problem, span, when } from "./ui.tsx";

function Timeline({ arc }: { arc: ArcView }) {
  const at = arc.phases.indexOf(arc.phase);
  return (
    <ol className="mt-3 flex flex-wrap items-center gap-1.5" aria-label="Phases">
      {arc.phases.map((p, i) => (
        <li key={p} aria-current={p === arc.phase ? "step" : undefined} className={`rounded-full px-2.5 py-1 text-xs font-semibold ${
          p === arc.phase ? "bg-brand text-white shadow-card" : i < at ? "bg-panel-2 text-muted" : "border border-line text-faint"
        }`}>
          {human(p)}
        </li>
      ))}
    </ol>
  );
}

function ArcCard({ arc, now }: { arc: ArcView; now: number }) {
  const [open, setOpen] = useState(false);
  const events = useArcEvents(arc.id, open);
  const secret = arc.visibility !== "PUBLIC";
  return (
    <Card className="p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="font-bold capitalize tracking-tight">{human(arc.defId)}</h3>
          <p className="mt-0.5 text-sm text-muted">{arc.summary}</p>
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          {secret && <Pill tone="violet"><Lock className="size-3" aria-hidden="true" /> {arc.visibility.toLowerCase()}</Pill>}
          {arc.sensitive && <Pill tone="accent">sensitive</Pill>}
          <Pill tone={arc.status === "ACTIVE" ? "good" : "plain"}>{arc.status.toLowerCase()}</Pill>
        </div>
      </div>
      <Timeline arc={arc} />
      <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 text-sm sm:grid-cols-3">
        <div>
          <dt className="text-xs text-muted">Started</dt>
          <dd className="tabular-nums">{span(arc.startedAt, now)}</dd>
        </div>
        <div>
          <dt className="text-xs text-muted">In this phase since</dt>
          <dd className="tabular-nums">{span(arc.phaseEnteredAt, now)}</dd>
        </div>
        <div>
          <dt className="text-xs text-muted">Next move</dt>
          <dd className="tabular-nums">{arc.status !== "ACTIVE" ? "—" : arc.nextTransitionAt ? span(arc.nextTransitionAt, now) : "whenever it happens"}</dd>
        </div>
      </dl>
      <div className="mt-3 flex flex-wrap items-center gap-x-5 gap-y-2">
        <div className="flex items-center gap-2">
          <span className="text-xs text-muted">In it</span>
          <span className="flex -space-x-1.5">{arc.people.map((p) => <Avatar key={p.id} actor={p} size="xs" />)}</span>
        </div>
        {secret && (
          <div className="flex items-center gap-2">
            <span className="text-xs text-muted">{arc.knowers.length === 0 ? "Nobody else knows" : "Who knows"}</span>
            <span className="flex -space-x-1.5">{arc.knowers.map((p) => <Avatar key={p.id} actor={p} size="xs" />)}</span>
          </div>
        )}
        {arc.constraints.map((c) => <Pill key={c}>{c}</Pill>)}
      </div>
      <button onClick={() => setOpen((o) => !o)} aria-expanded={open} className="mt-3 flex items-center gap-1 text-sm font-semibold text-muted transition hover:text-ink">
        <ChevronDown className={`size-4 transition ${open ? "rotate-180" : ""}`} aria-hidden="true" /> What's happened
      </button>
      {open && (
        <ol className="mt-2 space-y-1.5 border-l-2 border-line pl-3 text-sm">
          {events.isPending && <li className="text-muted">Loading…</li>}
          {events.data?.length === 0 && <li className="text-muted">Nothing logged.</li>}
          {events.data?.map((e) => (
            <li key={e.id}>
              <span className="font-medium">{human(e.type).toLowerCase()}</span>{" "}
              <span className="text-muted">· {when(e.at)}{e.status !== "AIRED" ? ` · ${e.status.toLowerCase()}` : ""}</span>
              {e.recapLine && <p className="text-muted">{e.recapLine}</p>}
            </li>
          ))}
        </ol>
      )}
    </Card>
  );
}

/** Every storyline, what phase it's in, what comes next and who's in on it. */
export function Storylines() {
  const arcs = useArcs();
  const [status, setStatus] = useState<ArcView["status"]>("ACTIVE");
  const [q, setQ] = useState("");
  if (arcs.isPending) return <Loading what="storylines" />;
  if (arcs.error) return <Problem error={arcs.error} />;
  const now = worldNow();
  const needle = q.trim().toLowerCase();
  const shown = arcs.data.filter((a) => a.status === status).filter((a) =>
    !needle || a.defId.includes(needle) || a.summary.toLowerCase().includes(needle) || a.people.some((p) => p.name.toLowerCase().includes(needle)));
  const count = (s: ArcView["status"]) => arcs.data.filter((a) => a.status === s).length;
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-3">
        <Segmented label="Status" value={status} onChange={setStatus} options={[
          { value: "ACTIVE", label: `Active ${count("ACTIVE")}` },
          { value: "RESOLVED", label: `Resolved ${count("RESOLVED")}` },
          { value: "ABANDONED", label: `Abandoned ${count("ABANDONED")}` },
        ]} />
        <label className="relative min-w-48 flex-1">
          <span className="sr-only">Search storylines</span>
          <Search className="absolute left-3 top-2.5 size-4 text-faint" aria-hidden="true" />
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Someone's name, or an arc" className={`${field} w-full pl-9`} />
        </label>
      </div>
      {shown.length === 0 && <Card><Empty icon={<Search className="size-6" />} title="No storylines here">Nothing {status.toLowerCase()} matches.</Empty></Card>}
      <div className="grid gap-3 lg:grid-cols-2">
        {shown.map((a) => <ArcCard key={a.id} arc={a} now={now} />)}
      </div>
    </div>
  );
}
