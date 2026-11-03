import { Ban, Check, Lock, RotateCcw } from "lucide-react";
import { useState } from "react";
import { Button, Segmented } from "../ui/kit.tsx";
import { type Knobs, type Settings, useSaveSettings, useSettings } from "./api.ts";
import { field, human, Loading, Pill, Problem, Section } from "./ui.tsx";

const LABELS: Record<string, string> = {
  dramaIntensity: "Drama intensity", maxMajorEventsPer14d: "Major events per person, 14 days", maxDramaPerDay: "Drama events per day", userSalience: "How much they notice you",
  maxUserShareOfAttention: "Most attention you can take", impressionSensitivity: "How fast impressions form", crushPropensity: "Crush propensity", dmInitiationRate: "How often they message first",
  edge: "Edge", temptation: "Temptation", headlinesPerDay: "Headlines per day", primeTimeBoost: "Prime-time boost", moodHalfLifeHours: "Mood half-life, hours", affinityHalfLifeDays: "Affinity half-life, days",
  maxTraitDrift: "Most a trait can drift", scriptSlackHours: "Script slack, hours",
};

function useSection() {
  const save = useSaveSettings();
  const [saved, setSaved] = useState(false);
  return {
    save,
    saved,
    send: (patch: Record<string, unknown>, then?: () => void) => {
      setSaved(false);
      save.mutate(patch, { onSuccess: () => { setSaved(true); then?.(); } });
    },
  };
}

function SaveBar({ dirty, section, onSave, onReset }: { dirty: boolean; section: ReturnType<typeof useSection>; onSave: () => void; onReset?: () => void }) {
  return (
    <div className="mt-3 flex flex-wrap items-center gap-2">
      <Button disabled={!dirty} busy={section.save.isPending} onClick={onSave}>Save</Button>
      {onReset && <Button variant="ghost" onClick={onReset}><RotateCcw className="size-4" aria-hidden="true" /> Back to the defaults</Button>}
      {section.saved && !dirty && <span role="status" className="flex items-center gap-1 text-sm font-medium text-good"><Check className="size-4" aria-hidden="true" /> Saved. It's live.</span>}
      <Problem error={section.save.error} />
    </div>
  );
}

function Numbers({ s }: { s: Settings }) {
  const section = useSection();
  const [draft, setDraft] = useState<Knobs>({});
  const value = (k: string) => (draft[k] ?? s.knobs[k]) as number;
  const changed = Object.keys(draft).filter((k) => draft[k] !== s.knobs[k]);
  // knobs replace as one section, so what's been saved goes along
  const overrides = () => {
    const out: Knobs = {};
    for (const k of Object.keys(s.ranges)) if (s.knobs[k] !== s.knobDefaults[k]) out[k] = s.knobs[k]!;
    for (const k of ["profile", "affairs", "eventMix", "regionMix", "contentKindWeights"]) if (JSON.stringify(s.knobs[k]) !== JSON.stringify(s.knobDefaults[k])) out[k] = s.knobs[k]!;
    return { ...out, ...draft };
  };
  const profile = (draft.profile ?? s.knobs.profile) as string;
  const affairs = (draft.affairs ?? s.knobs.affairs) as boolean;
  return (
    <Section title="World" hint="The next plan uses these. What's already on the script stays as written.">
      <div className="mb-4 flex flex-wrap items-center gap-4">
        <div>
          <p className="mb-1 text-xs font-semibold text-muted">Storyteller</p>
          <Segmented label="Storyteller" value={profile} onChange={(p) => setDraft({ ...draft, profile: p })} options={s.profiles.map((p) => ({ value: p, label: human(p).toLowerCase() }))} />
        </div>
        <label className="flex items-center gap-2 text-sm font-semibold">
          <input type="checkbox" checked={affairs} onChange={(e) => setDraft({ ...draft, affairs: e.target.checked })} className="accent-[var(--accent)]" /> Affairs
        </label>
      </div>
      <div className="grid gap-x-8 gap-y-3 md:grid-cols-2">
        {Object.entries(s.ranges).map(([k, r]) => (
          <label key={k} className="block text-sm">
            <span className="flex justify-between gap-2">
              <span className="text-muted">{LABELS[k] ?? k}</span>
              <span className="tabular-nums font-semibold">{value(k)}</span>
            </span>
            <input
              type="range" aria-label={LABELS[k] ?? k} min={r.min} max={r.max} step={r.whole ? 1 : (r.max - r.min) / 100}
              value={value(k)} onChange={(e) => setDraft({ ...draft, [k]: Number(e.target.value) })} className="mt-1 w-full accent-[var(--accent)]"
            />
          </label>
        ))}
      </div>
      <SaveBar dirty={changed.length > 0} section={section} onSave={() => section.send({ knobs: overrides() }, () => setDraft({}))} onReset={() => section.send({ knobs: null }, () => setDraft({}))} />
    </Section>
  );
}

function MixEditor({ s, name, title, hint }: { s: Settings; name: "eventMix" | "regionMix" | "contentKindWeights"; title: string; hint: string }) {
  const section = useSection();
  const saved = s.knobs[name] as Record<string, number>;
  const [draft, setDraft] = useState<Record<string, number> | null>(null);
  const mix = draft ?? saved;
  const total = Object.values(mix).reduce((n, v) => n + v, 0);
  const keep = () => {
    const out: Knobs = {};
    for (const k of Object.keys(s.knobs)) if (JSON.stringify(s.knobs[k]) !== JSON.stringify(s.knobDefaults[k])) out[k] = s.knobs[k]!;
    return out;
  };
  return (
    <Section title={title} hint={hint}>
      <div className="space-y-2">
        {Object.entries(mix).map(([k, v]) => (
          <label key={k} className="grid grid-cols-[6rem_1fr_3rem] items-center gap-3 text-sm">
            <span className="truncate text-muted">{human(k).toLowerCase()}</span>
            <input type="range" aria-label={k} min={0} max={name === "contentKindWeights" ? 3 : 1} step={0.01} value={v} onChange={(e) => setDraft({ ...mix, [k]: Number(e.target.value) })} className="w-full accent-[var(--accent)]" />
            <span className="text-right tabular-nums">{name === "contentKindWeights" ? v.toFixed(2) : `${total ? Math.round((v / total) * 100) : 0}%`}</span>
          </label>
        ))}
      </div>
      <SaveBar dirty={draft !== null} section={section} onSave={() => section.send({ knobs: { ...keep(), [name]: mix } }, () => setDraft(null))} />
    </Section>
  );
}

function Blacklist({ s }: { s: Settings }) {
  const section = useSection();
  const blocked = new Map(s.blacklist.map((b) => [b.id, b.locked]));
  const send = (ids: string[]) => section.send({ blacklist: ids });
  const mine = s.blacklist.filter((b) => !b.locked).map((b) => b.id);
  const locked = s.blacklist.filter((b) => b.locked);
  return (
    <Section title="Arc blacklist" hint="A blocked arc can't start from this moment on: starts already on the script are dropped when they come up, and running ones wind down at the next plan.">
      <div className="flex flex-wrap gap-1.5">
        {s.arcs.map((id) => {
          const on = blocked.has(id);
          const fixed = blocked.get(id) === true;
          return (
            <button
              key={id} aria-pressed={on} disabled={fixed || section.save.isPending} onClick={() => send(on ? mine.filter((x) => x !== id) : [...mine, id])}
              className={`inline-flex items-center gap-1 rounded-full px-3 py-1 text-sm font-semibold transition disabled:cursor-default ${on ? "bg-bad/15 text-bad" : "bg-panel-2 text-muted hover:text-ink"}`}
            >
              {on && (fixed ? <Lock className="size-3" aria-hidden="true" /> : <Ban className="size-3" aria-hidden="true" />)} {human(id)}
            </button>
          );
        })}
      </div>
      {locked.length > 0 && (
        <p className="mt-3 flex flex-wrap items-center gap-1.5 text-xs text-muted">Ruled out in the config, for good: {locked.map((b) => <Pill key={b.id}><Lock className="size-3" aria-hidden="true" /> {human(b.id)}</Pill>)}</p>
      )}
      <div className="mt-2"><Problem error={section.save.error} /></div>
    </Section>
  );
}

function Routes({ s }: { s: Settings }) {
  const section = useSection();
  const [draft, setDraft] = useState<Settings["routes"]>({});
  const route = (p: string) => draft[p] ?? s.routes[p]!;
  const dirty = Object.keys(draft).some((p) => JSON.stringify(draft[p]) !== JSON.stringify(s.routes[p]));
  return (
    <Section title="LLM routes" hint="Which model each purpose asks first, and who's next. The next call uses them.">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[560px] text-left text-sm">
          <thead className="text-xs uppercase tracking-wider text-muted">
            <tr><th className="py-2 pr-3">Purpose</th><th className="py-2 pr-3">Model</th><th className="py-2">Fallbacks, in order</th></tr>
          </thead>
          <tbody className="divide-y divide-line">
            {Object.keys(s.routes).map((p) => (
              <tr key={p}>
                <td className="py-2 pr-3 font-medium">{human(p)}</td>
                <td className="py-2 pr-3">
                  <select aria-label={`${p} model`} value={route(p).model} onChange={(e) => setDraft({ ...draft, [p]: { ...route(p), model: e.target.value } })} className={field}>
                    {s.models.map((m) => <option key={m.alias} value={m.alias}>{m.alias}{m.available ? "" : " (no key)"}</option>)}
                  </select>
                </td>
                <td className="py-2">
                  <input
                    aria-label={`${p} fallbacks`} value={route(p).fallback.join(", ")} className={`${field} w-full`}
                    onChange={(e) => setDraft({ ...draft, [p]: { ...route(p), fallback: e.target.value.split(",").map((x) => x.trim()).filter(Boolean) } })}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <SaveBar dirty={dirty} section={section} onSave={() => section.send({ routes: { ...s.routes, ...draft } }, () => setDraft({}))} onReset={() => section.send({ routes: null }, () => setDraft({}))} />
    </Section>
  );
}

function Limits({ s }: { s: Settings }) {
  const section = useSection();
  const [budgets, setBudgets] = useState(s.budgets);
  const [images, setImages] = useState(s.imagesPerDay);
  const dirty = images !== s.imagesPerDay || JSON.stringify(budgets) !== JSON.stringify(s.budgets);
  const money = (label: string, k: keyof typeof budgets) => (
    <label className="text-sm">
      <span className="text-muted">{label}</span>
      <input aria-label={label} type="number" min={0} step={0.1} value={budgets[k]} onChange={(e) => setBudgets({ ...budgets, [k]: Number(e.target.value) })} className={`${field} mt-1 block w-full tabular-nums`} />
    </label>
  );
  return (
    <Section title="Budgets" hint="Dollars per window, and how many pictures a day. Live chat only ever answers to the monthly cap.">
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        {money("Daily, $", "dailyUsd")}
        {money("Per batch, $", "perBatchUsd")}
        {money("Monthly, $", "monthlyUsd")}
        <label className="text-sm">
          <span className="text-muted">Images per day</span>
          <input aria-label="Images per day" type="number" min={0} step={1} value={images} onChange={(e) => setImages(Number(e.target.value))} className={`${field} mt-1 block w-full tabular-nums`} />
        </label>
      </div>
      <SaveBar dirty={dirty} section={section} onSave={() => section.send({ budgets, imagesPerDay: images })} />
    </Section>
  );
}

function Pacing({ s }: { s: Settings }) {
  const section = useSection();
  const text = JSON.stringify(s.pacing, null, 2);
  const [draft, setDraft] = useState(text);
  const [broken, setBroken] = useState(false);
  return (
    <Section title="Pacing rules" hint="Your next session uses these; the one you're in keeps what it planned.">
      <textarea
        aria-label="Pacing rules" value={draft} rows={14} spellCheck={false}
        onChange={(e) => { setDraft(e.target.value); setBroken(false); }} className={`${field} w-full resize-y font-mono text-xs`}
      />
      {broken && <p role="alert" className="mt-1 text-sm font-medium text-bad">That isn't valid JSON.</p>}
      <SaveBar
        dirty={draft !== text} section={section}
        onSave={() => {
          try {
            section.send({ pacing: JSON.parse(draft) as unknown });
          } catch {
            setBroken(true);
          }
        }}
        onReset={() => section.send({ pacing: null }, () => setDraft(text))}
      />
    </Section>
  );
}

/** Everything that can be tuned while the world runs; it's kept across restarts. */
export function KnobsPanel() {
  const settings = useSettings();
  if (settings.isPending) return <Loading what="the settings" />;
  if (settings.error) return <Problem error={settings.error} />;
  const s = settings.data;
  return (
    <div className="space-y-3">
      <Numbers s={s} />
      <div className="grid gap-3 lg:grid-cols-3">
        <MixEditor s={s} name="eventMix" title="Event mix" hint="What the storyteller reaches for." />
        <MixEditor s={s} name="regionMix" title="News regions" hint="Where the headlines come from." />
        <MixEditor s={s} name="contentKindWeights" title="Content weights" hint="How much attention each kind gets." />
      </div>
      <Blacklist s={s} />
      <Routes s={s} />
      <Limits s={s} />
      <Pacing key={JSON.stringify(s.pacing)} s={s} />
    </div>
  );
}
