import { FastForward, Pause, Play, Zap } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { Button, Segmented } from "../ui/kit.tsx";
import { type Injection, useInject, useInjectables, useTime, useTimeAction } from "./api.ts";
import { field, human, Loading, Pill, Problem, Section, span, when } from "./ui.tsx";

function Clock() {
  const time = useTime();
  const act = useTimeAction();
  const [hours, setHours] = useState(6);
  if (time.isPending) return <Loading what="the clock" />;
  if (time.error) return <Problem error={time.error} />;
  const t = time.data;
  return (
    <Section title="Time" action={t.paused ? <Pill tone="accent"><Pause className="size-3" aria-hidden="true" /> paused</Pill> : <Pill tone="good">running</Pill>}>
      <p className="text-3xl font-extrabold tabular-nums tracking-tight">{when(t.worldTime)}</p>
      <p className="mt-1 text-sm text-muted">
        Script reaches {span(t.scriptedUntil, t.worldTime)}.
        {t.offsetMs !== 0 && ` The world is ${span(Math.abs(t.offsetMs), 0).replace("in ", "")} ${t.offsetMs > 0 ? "ahead of" : "behind"} real time.`}
        {t.backfilling && " Catching up now."}
      </p>
      <div className="mt-4 flex flex-wrap items-center gap-2">
        {t.paused ? (
          <Button busy={act.isPending} onClick={() => act.mutate({ action: "resume" })}><Play className="size-4" aria-hidden="true" /> Resume</Button>
        ) : (
          <Button variant="soft" busy={act.isPending} onClick={() => act.mutate({ action: "pause" })}><Pause className="size-4" aria-hidden="true" /> Pause the world</Button>
        )}
        <span className="mx-1 h-6 w-px bg-line" aria-hidden="true" />
        {[1, 6, 24].map((h) => (
          <Button key={h} variant="soft" disabled={act.isPending} onClick={() => act.mutate({ action: "fast-forward", hours: h })}>
            <FastForward className="size-4" aria-hidden="true" /> {h}h
          </Button>
        ))}
        <label className="flex items-center gap-2 text-sm">
          <span className="sr-only">Hours to fast-forward</span>
          <input aria-label="Hours to fast-forward" type="number" min={1} max={336} value={hours} onChange={(e) => setHours(Number(e.target.value))} className={`${field} w-20 tabular-nums`} />
          <Button variant="soft" disabled={act.isPending} onClick={() => act.mutate({ action: "fast-forward", hours })}>Go</Button>
        </label>
      </div>
      <p className="mt-2 text-xs text-muted">What's scripted airs at once; past the end of the script, a backfill fills in. Paused, nothing airs and nobody answers.</p>
      <div className="mt-2"><Problem error={act.error} /></div>
    </Section>
  );
}

type Kind = Injection["kind"];

function Injector() {
  const data = useInjectables();
  const inject = useInject();
  const act = useTimeAction();
  const [kind, setKind] = useState<Kind>("BREAKUP");
  const [couple, setCouple] = useState(0);
  const [defId, setDefId] = useState("");
  const [actor, setActor] = useState("");
  const [other, setOther] = useState("");
  if (data.isPending) return <Loading what="what can be fired" />;
  if (data.error) return <Problem error={data.error} />;
  const { events, arcs, residents, couples } = data.data;
  const pair = couples[couple] ?? couples[0];
  const event = events.find((e) => e.id === defId);
  const arc = arcs.find((a) => a.id === defId);
  const needsOther = kind === "EVENT" ? Boolean(event?.otherRole) : kind === "ARC" ? Boolean(arc?.needsOther) : false;

  const ready = kind === "BREAKUP" ? Boolean(pair) : Boolean(defId && actor && (!needsOther || other));
  const fire = () => {
    if (kind === "BREAKUP") {
      if (pair) inject.mutate({ kind, actor: pair[0]!.id, other: pair[1]!.id }, { onSuccess: () => setCouple(0) });
    } else {
      inject.mutate({ kind, defId, actor, ...(needsOther ? { other } : {}) });
    }
  };
  const people = (value: string, set: (v: string) => void, label: string) => (
    <label className="text-sm">
      <span className="text-muted">{label}</span>
      <select aria-label={label} value={value} onChange={(e) => set(e.target.value)} className={`${field} mt-1 block w-full`}>
        <option value="">Choose…</option>
        {residents.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
      </select>
    </label>
  );

  return (
    <Section title="Incident injector" hint="Fire anything, on anyone. It's scripted like everything else, so it airs when its time comes.">
      <Segmented label="What to fire" value={kind} onChange={(k) => { setKind(k); setDefId(""); inject.reset(); }} options={[
        { value: "BREAKUP", label: "Breakup" }, { value: "EVENT", label: "Event" }, { value: "ARC", label: "Storyline" },
      ]} />
      <div className="mt-3 grid gap-3 md:grid-cols-2">
        {kind === "BREAKUP" && (
          couples.length === 0 ? <p className="text-sm text-muted">Nobody's together right now.</p> : (
            <label className="text-sm md:col-span-2">
              <span className="text-muted">Which couple</span>
              <select aria-label="Which couple" value={couple} onChange={(e) => setCouple(Number(e.target.value))} className={`${field} mt-1 block w-full`}>
                {couples.map((c, i) => <option key={c.map((p) => p.id).join()} value={i}>{c.map((p) => p.name).join(" and ")}</option>)}
              </select>
            </label>
          )
        )}
        {kind === "EVENT" && (
          <>
            <label className="text-sm md:col-span-2">
              <span className="text-muted">Which event</span>
              <select aria-label="Which event" value={defId} onChange={(e) => setDefId(e.target.value)} className={`${field} mt-1 block w-full`}>
                <option value="">Choose…</option>
                {events.map((e) => <option key={e.id} value={e.id}>{human(e.id)} ({e.category.toLowerCase()})</option>)}
              </select>
              {event?.description && <span className="mt-1 block text-xs text-muted">{event.description}</span>}
            </label>
            {people(actor, setActor, "Who it happens to")}
            {needsOther && people(other, setOther, `Who else (${event?.otherRole})`)}
          </>
        )}
        {kind === "ARC" && (
          <>
            <label className="text-sm md:col-span-2">
              <span className="text-muted">Which storyline</span>
              <select aria-label="Which storyline" value={defId} onChange={(e) => setDefId(e.target.value)} className={`${field} mt-1 block w-full`}>
                <option value="">Choose…</option>
                {arcs.map((a) => <option key={a.id} value={a.id} disabled={a.blacklisted}>{human(a.id)}{a.blacklisted ? " (blacklisted)" : ""}</option>)}
              </select>
              {arc?.description && <span className="mt-1 block text-xs text-muted">{arc.description}</span>}
            </label>
            {people(actor, setActor, "Whose story it is")}
            {needsOther && people(other, setOther, "Who else")}
          </>
        )}
      </div>
      <div className="mt-4 flex flex-wrap items-center gap-2">
        <Button disabled={!ready} busy={inject.isPending} onClick={fire}><Zap className="size-4" aria-hidden="true" /> Fire it</Button>
      </div>
      <div className="mt-2"><Problem error={inject.error} /></div>
      {inject.data && (
        <div role="status" className="animate-rise mt-3 rounded-xl bg-accent-soft px-4 py-3 text-sm">
          <p className="font-semibold">
            Scripted: {inject.data.events} event{inject.data.events === 1 ? "" : "s"}, {inject.data.posts} post{inject.data.posts === 1 ? "" : "s"}, {inject.data.comments} comments, {inject.data.reactions} reactions.
          </p>
          <p className="mt-0.5 text-muted">It starts within a minute and plays out over the next hours.</p>
          <div className="mt-2 flex flex-wrap gap-2">
            <Button variant="soft" busy={act.isPending} onClick={() => act.mutate({ action: "fast-forward", hours: 6 })}><FastForward className="size-4" aria-hidden="true" /> Six hours on</Button>
            <Link to="/" className="inline-flex items-center rounded-xl px-4 py-2 text-sm font-semibold text-accent hover:underline">Watch the feed</Link>
          </div>
        </div>
      )}
    </Section>
  );
}

/** Time and the injector together: fire something, then move the clock and watch. */
export function TimePanel() {
  return (
    <div className="space-y-3">
      <Clock />
      <Injector />
    </div>
  );
}
