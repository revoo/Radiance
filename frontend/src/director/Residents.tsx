import { FileText, Search, UserRound } from "lucide-react";
import { useState } from "react";
import { Link, NavLink, useParams } from "react-router";
import { useWorldNow } from "../live/store.ts";
import { Avatar, Card, Empty } from "../ui/kit.tsx";
import { type Inspector as InspectorData, useInjectables, useInspector } from "./api.ts";
import { field, human, Loading, Pill, Problem, Section, span, Spark } from "./ui.tsx";

function Meter({ label, value, min = 0, max = 1 }: { label: string; value: number; min?: number; max?: number }) {
  const at = ((Math.min(max, Math.max(min, value)) - min) / (max - min)) * 100;
  return (
    <div>
      <div className="flex justify-between text-xs"><span className="text-muted">{label}</span><span className="tabular-nums">{value.toFixed(2)}</span></div>
      <div className="mt-1 h-2 overflow-hidden rounded-full bg-panel-2"><div className="h-full rounded-full bg-[var(--viz-pos)]" style={{ width: `${at}%` }} /></div>
    </div>
  );
}

/** Where a trait started, and where it's drifted to. */
function Trait({ name, base, offset }: { name: string; base: number; offset: number }) {
  const now = Math.max(0, Math.min(100, base + offset));
  return (
    <div className="grid grid-cols-[minmax(0,8rem)_1fr_3.5rem] items-center gap-3 text-sm" title={`${name}: baseline ${base.toFixed(0)}, now ${now.toFixed(0)}`}>
      <span className="truncate capitalize text-muted">{human(name)}</span>
      <span className="relative h-2 rounded-full bg-panel-2">
        <span className="absolute inset-y-0 left-0 rounded-full bg-[var(--viz-pos)]" style={{ width: `${now}%` }} />
        <span className="absolute -inset-y-1 w-0.5 rounded-full bg-ink" style={{ left: `${base}%` }} aria-hidden="true" />
      </span>
      <span className="text-right tabular-nums">{now.toFixed(0)}{offset ? <span className="text-xs text-muted"> {offset > 0 ? "+" : ""}{offset.toFixed(0)}</span> : null}</span>
    </div>
  );
}

function Raw({ title, value }: { title: string; value: unknown }) {
  return (
    <details className="rounded-xl bg-panel-2 px-3 py-2 text-sm">
      <summary className="cursor-pointer font-semibold">{title}</summary>
      <pre className="mt-2 max-h-72 overflow-auto whitespace-pre-wrap break-words text-xs text-muted">{JSON.stringify(value, null, 2)}</pre>
    </details>
  );
}

function Sheet({ d }: { d: InspectorData }) {
  const now = useWorldNow();
  return (
    <div className="space-y-3">
      <Card className="p-4 sm:p-5">
        <div className="flex flex-wrap items-center gap-3">
          <Avatar actor={d.who} size="lg" />
          <div className="min-w-0 flex-1">
            <h2 className="text-xl font-extrabold tracking-tight">{d.who.name}</h2>
            <p className="text-sm text-muted">{[d.occupation, d.livesIn, d.relationshipStatus && human(d.relationshipStatus).toLowerCase()].filter(Boolean).join(" · ")}</p>
          </div>
          <Link to={`/director/prompts?q=${encodeURIComponent(`@${d.who.handle}`)}`} className="flex items-center gap-1.5 rounded-xl bg-panel-2 px-3 py-2 text-sm font-semibold transition hover:bg-line">
            <FileText className="size-4" aria-hidden="true" /> Recent prompts
          </Link>
        </div>
        {d.bio && <p className="mt-3 text-sm">{d.bio}</p>}
        <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Meter label="Mood" value={d.mood} min={-1} />
          <Meter label="Energy" value={d.energy} />
          <Meter label="Stress" value={d.stress} />
          {d.partnerSatisfaction !== null && <Meter label="With their partner" value={d.partnerSatisfaction} />}
        </div>
        <p className="mt-3 text-sm text-muted">Right now: {human(d.location).toLowerCase()}</p>
      </Card>
      <div className="grid gap-3 lg:grid-cols-2">
        <Section title="How they've been" hint="One point a night.">
          <div className="space-y-3">
            <Spark title="Mood" min={-1} max={1} points={d.history.filter((h) => h.mood !== null).map((h) => ({ day: h.day, value: h.mood! }))} />
            <Spark title="Like-meter for you" min={-100} max={100} points={d.history.map((h) => ({ day: h.day, value: h.affinity }))} />
          </div>
        </Section>
        <Section title="What they make of you" hint={d.impressionSummary ?? undefined}>
          {!d.impressionOfR && <p className="text-sm text-muted">They don't know you yet.</p>}
          {d.impressionOfR && (
            <>
              <div className="grid grid-cols-2 gap-3">
                <Meter label="Affinity" value={d.impressionOfR.affinity} min={-100} max={100} />
                <Meter label="Trust" value={d.impressionOfR.trust} max={100} />
                <Meter label="Attraction" value={d.impressionOfR.attraction} max={100} />
                <Meter label="Respect" value={d.impressionOfR.respect} min={-100} max={100} />
              </div>
              <div className="mt-3 flex flex-wrap gap-1.5">
                <Pill>{human(d.impressionOfR.kind).toLowerCase()}</Pill>
                {d.impressionOfR.tags.map((t) => <Pill key={t} tone="violet">{t}</Pill>)}
              </div>
            </>
          )}
        </Section>
      </div>
      <Section title="Traits" hint="The tick is where they started; the bar is where they are now.">
        <div className="grid gap-x-8 gap-y-2 md:grid-cols-2">
          {Object.entries(d.traits).map(([name, base]) => <Trait key={name} name={name} base={base} offset={d.traitOffsets[name] ?? 0} />)}
        </div>
      </Section>
      <div className="grid gap-3 lg:grid-cols-2">
        <Section title="Constraints">
          {d.constraints.length === 0 ? <p className="text-sm text-muted">None right now.</p> : <div className="flex flex-wrap gap-1.5">{d.constraints.map((c) => <Pill key={c}>{c}</Pill>)}</div>}
        </Section>
        <Section title="Things that matter to them">
          {d.entities.length === 0 && <p className="text-sm text-muted">Nothing tracked.</p>}
          <ul className="space-y-1.5 text-sm">
            {d.entities.map((e) => (
              <li key={e.id}><span className="font-medium">{e.name}</span> <span className="text-muted">· {e.kind.toLowerCase()} · {e.status.toLowerCase()}</span>{e.description && <p className="text-xs text-muted">{e.description}</p>}</li>
            ))}
          </ul>
        </Section>
      </div>
      <Section title="Memories" hint="Highest score first: what a prompt about them and you would pull up.">
        {d.memories.length === 0 && <p className="text-sm text-muted">Nothing yet.</p>}
        <ul className="divide-y divide-line">
          {d.memories.map((m) => (
            <li key={m.id} className="flex items-start gap-3 py-2 text-sm">
              <span className="w-10 shrink-0 text-right font-semibold tabular-nums" title="Retrieval score">{m.score.toFixed(2)}</span>
              <span className="min-w-0 flex-1">{m.text} <span className="text-xs text-muted">· {span(m.at, now)} · importance {m.importance}</span></span>
            </li>
          ))}
        </ul>
      </Section>
      <Section title="Who they are">
        <p className="whitespace-pre-line text-sm">{d.backstory}</p>
        {d.lately && <p className="mt-2 text-sm text-muted">Lately: {d.lately}</p>}
        <div className="mt-3 grid gap-2 md:grid-cols-2">
          <Raw title="Voice" value={d.voice} />
          <Raw title="Temperament" value={d.temperament} />
          <Raw title="Politics" value={d.politics} />
          <Raw title="Routine" value={d.routine} />
          <Raw title="Interests" value={d.interests} />
          <Raw title="Quirks" value={d.quirks} />
        </div>
      </Section>
    </div>
  );
}

/** Pick a resident on the left, see everything about them on the right. */
export function Residents() {
  const { sub } = useParams();
  const people = useInjectables();
  const sheet = useInspector(sub);
  const [q, setQ] = useState("");
  const list = (people.data?.residents ?? []).filter((p) => p.name.toLowerCase().includes(q.trim().toLowerCase()));
  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-3 lg:grid-cols-[250px_minmax(0,1fr)]">
      <Card className="p-2 lg:sticky lg:top-20 lg:max-h-[calc(100vh-6rem)] lg:overflow-y-auto">
        <label className="relative block p-1">
          <span className="sr-only">Find a resident</span>
          <Search className="absolute left-4 top-3.5 size-4 text-faint" aria-hidden="true" />
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Find a resident" className={`${field} w-full pl-9`} />
        </label>
        <ul className="mt-1 flex gap-1 overflow-x-auto lg:block lg:space-y-0.5">
          {list.map((p) => (
            <li key={p.id} className="shrink-0">
              <NavLink to={`/director/residents/${p.handle}`} className={({ isActive }) => `flex items-center gap-2 rounded-xl px-2 py-1.5 text-sm font-medium transition ${isActive ? "bg-accent-soft text-accent" : "hover:bg-panel-2"}`}>
                <Avatar actor={p} size="xs" link={false} /> <span className="truncate">{p.name}</span>
              </NavLink>
            </li>
          ))}
        </ul>
      </Card>
      <div className="min-w-0">
        {!sub && <Card><Empty icon={<UserRound className="size-6" />} title="Pick someone">Everything the world knows about them shows here.</Empty></Card>}
        {sub && sheet.isPending && <Loading what="them" />}
        {sub && sheet.error && <Problem error={sheet.error} />}
        {sheet.data && <Sheet d={sheet.data} />}
      </div>
    </div>
  );
}
