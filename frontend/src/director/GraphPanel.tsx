import { useMemo, useState } from "react";
import { Link } from "react-router";
import { Avatar, Card } from "../ui/kit.tsx";
import { useEdge, useGraph } from "./api.ts";
import { type Dimension, filterTies, forceLayout, isTwoSided, tieColor, tiesFrom, tieWidth } from "./layout.ts";
import { field, human, Loading, Pill, Problem, when } from "./ui.tsx";

const W = 860, H = 620;
const DIMENSIONS: Dimension[] = ["affinity", "trust", "attraction", "familiarity", "respect"];
const KINDS = ["PARTNER", "EX", "FAMILY", "CLOSE_FRIEND", "FRIEND", "COWORKER", "ACQUAINTANCE", "RIVAL", "STRANGER"];

function EdgePanel({ a, b, onClose }: { a: string; b: string; onClose: () => void }) {
  const edge = useEdge(a, b);
  if (edge.isPending) return <Card className="p-4"><Loading what="their history" /></Card>;
  if (edge.error) return <Problem error={edge.error} />;
  const { from, to, there, back, summary, events } = edge.data;
  const row = (label: string, x?: number, y?: number) => (
    <tr key={label}>
      <th scope="row" className="py-0.5 pr-3 text-left font-normal capitalize text-muted">{label}</th>
      <td className="py-0.5 pr-3 tabular-nums">{x?.toFixed(0) ?? "—"}</td>
      <td className="py-0.5 tabular-nums">{y?.toFixed(0) ?? "—"}</td>
    </tr>
  );
  return (
    <Card className="p-4">
      <div className="flex items-center justify-between gap-2">
        <h3 className="flex items-center gap-2 font-bold">
          <Avatar actor={from} size="xs" /> {from.name} <span className="text-faint">↔</span> <Avatar actor={to} size="xs" /> {to.name}
        </h3>
        <button onClick={onClose} className="rounded-lg px-2 py-1 text-sm font-semibold text-muted hover:bg-panel-2">Close</button>
      </div>
      <div className="mt-2 flex flex-wrap gap-1.5">
        {there && <Pill>{human(there.kind).toLowerCase()}</Pill>}
        {[...new Set([...(there?.tags ?? []), ...(back?.tags ?? [])])].map((t) => <Pill key={t} tone="violet">{t}</Pill>)}
      </div>
      {summary && <p className="mt-2 text-sm text-muted">{summary}</p>}
      <table className="mt-3 text-sm">
        <thead>
          <tr className="text-xs text-muted">
            <th />
            <th className="pr-3 text-left font-semibold">{from.name.split(" ")[0]} → {to.name.split(" ")[0]}</th>
            <th className="text-left font-semibold">{to.name.split(" ")[0]} → {from.name.split(" ")[0]}</th>
          </tr>
        </thead>
        <tbody>{DIMENSIONS.map((d) => row(d, there?.[d], back?.[d]))}</tbody>
      </table>
      <h4 className="mt-3 text-sm font-bold">Between them</h4>
      {events.length === 0 && <p className="text-sm text-muted">Nothing logged between these two.</p>}
      <ol className="mt-1 space-y-1 text-sm">
        {events.map((e) => (
          <li key={e.id}><span className="font-medium">{human(e.type).toLowerCase()}</span> <span className="text-muted">· {when(e.at)}</span></li>
        ))}
      </ol>
    </Card>
  );
}

/** Who feels what about whom: everyone on one canvas, R among them, each tie colored by the dimension chosen. */
export function GraphPanel() {
  const graph = useGraph();
  const [dimension, setDimension] = useState<Dimension>("affinity");
  const [kind, setKind] = useState("");
  const [tag, setTag] = useState("");
  const [min, setMin] = useState(30);
  const [connected, setConnected] = useState(false);
  const [focus, setFocus] = useState<string | null>(null);
  const [picked, setPicked] = useState<[string, string] | null>(null);

  const all = useMemo(() => tiesFrom(graph.data?.edges ?? [], dimension), [graph.data, dimension]);
  // places come from friendships, not the filters: people stay put while R looks around
  const places = useMemo(() => {
    const base = tiesFrom(graph.data?.edges ?? [], "familiarity");
    return forceLayout((graph.data?.nodes ?? []).map((n) => n.id), base.filter((t) => t.value >= 25).map((t) => ({ a: t.a, b: t.b, weight: t.value / 100 })), { width: W, height: H });
  }, [graph.data]);
  const shown = useMemo(() => filterTies(all, { kind, tag, min, connected }), [all, kind, tag, min, connected]);

  if (graph.isPending) return <Loading what="the graph" />;
  if (graph.error) return <Problem error={graph.error} />;
  const nodes = graph.data.nodes;
  const lit = (t: { a: string; b: string }) => !focus || t.a === focus || t.b === focus;
  const focused = nodes.find((n) => n.id === focus);

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-end gap-3">
        <label className="text-xs font-semibold text-muted">
          Color by
          <select aria-label="Color by" value={dimension} onChange={(e) => setDimension(e.target.value as Dimension)} className={`${field} mt-1 block capitalize`}>
            {DIMENSIONS.map((d) => <option key={d} value={d}>{d}</option>)}
          </select>
        </label>
        <label className="text-xs font-semibold text-muted">
          Kind
          <select aria-label="Kind" value={kind} onChange={(e) => setKind(e.target.value)} className={`${field} mt-1 block`}>
            <option value="">All</option>
            {KINDS.map((k) => <option key={k} value={k}>{human(k).toLowerCase()}</option>)}
          </select>
        </label>
        <label className="text-xs font-semibold text-muted">
          Tag
          <input aria-label="Tag" value={tag} onChange={(e) => setTag(e.target.value)} placeholder="crush, rival…" className={`${field} mt-1 block w-36`} />
        </label>
        <label className="text-xs font-semibold text-muted">
          At least {min}
          <input aria-label="Minimum strength" type="range" min={0} max={100} value={min} onChange={(e) => setMin(Number(e.target.value))} className="mt-3 block w-32 accent-[var(--accent)]" />
        </label>
        <label className="flex items-center gap-2 pb-2 text-sm font-semibold text-muted">
          <input type="checkbox" checked={connected} onChange={(e) => setConnected(e.target.checked)} className="accent-[var(--accent)]" /> Friends on the network
        </label>
        <p className="ml-auto pb-2 text-xs tabular-nums text-muted">{shown.length} ties</p>
      </div>
      <div className="grid gap-3 xl:grid-cols-[minmax(0,1fr)_340px]">
        <Card className="overflow-hidden p-1">
          <svg viewBox={`0 0 ${W} ${H}`} className="block h-auto w-full" role="img" aria-label={`Relationship graph: ${nodes.length} people, ${shown.length} ties by ${dimension}`}>
            {shown.map((t) => {
              const a = places[t.a], b = places[t.b];
              if (!a || !b) return null;
              const on = lit(t);
              return (
                <g key={`${t.a}|${t.b}`} opacity={on ? 1 : 0.08}>
                  <line x1={a.x} y1={a.y} x2={b.x} y2={b.y} stroke={tieColor(t.value, dimension)} strokeWidth={tieWidth(t.value)} strokeLinecap="round" />
                  <line
                    data-testid="tie" x1={a.x} y1={a.y} x2={b.x} y2={b.y} stroke="transparent" strokeWidth={10} className="cursor-pointer"
                    onClick={() => setPicked([t.a, t.b])}
                  >
                    <title>{`${nodes.find((n) => n.id === t.a)?.name} and ${nodes.find((n) => n.id === t.b)?.name}: ${dimension} ${t.value.toFixed(0)}`}</title>
                  </line>
                </g>
              );
            })}
            {nodes.map((n) => {
              const p = places[n.id];
              if (!p) return null;
              const near = !focus || n.id === focus || shown.some((t) => (t.a === focus && t.b === n.id) || (t.b === focus && t.a === n.id));
              const r = n.you ? 16 : 12;
              return (
                <g
                  key={n.id} opacity={near ? 1 : 0.25} className="cursor-pointer" role="button" tabIndex={0} aria-label={n.name} aria-pressed={n.id === focus}
                  onClick={() => setFocus(focus === n.id ? null : n.id)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" || e.key === " ") {
                      e.preventDefault();
                      setFocus(focus === n.id ? null : n.id);
                    }
                  }}
                >
                  <circle cx={p.x} cy={p.y} r={r} fill={n.you ? "var(--accent)" : "var(--panel-2)"} stroke={n.id === focus ? "var(--accent)" : "var(--panel)"} strokeWidth={2} />
                  <text x={p.x} y={p.y + 3.5} textAnchor="middle" fontSize={10} fontWeight={700} fill={n.you ? "#fff" : "var(--ink)"}>
                    {n.name.split(" ").map((x) => x[0]).slice(0, 2).join("")}
                  </text>
                  <text x={p.x} y={p.y + r + 12} textAnchor="middle" fontSize={10} fill="var(--muted)" stroke="var(--panel)" strokeWidth={3} paintOrder="stroke">{n.name.split(" ")[0]}</text>
                  <title>{`${n.name}, mood ${n.mood.toFixed(2)}`}</title>
                </g>
              );
            })}
          </svg>
          <div className="flex flex-wrap items-center gap-3 px-3 pb-2 text-xs text-muted">
            {isTwoSided(dimension) ? (
              <>
                <span className="flex items-center gap-1.5"><span className="h-1 w-5 rounded-full bg-[var(--viz-neg)]" /> Negative</span>
                <span className="flex items-center gap-1.5"><span className="h-1 w-5 rounded-full bg-[var(--viz-mid)]" /> Neutral</span>
                <span className="flex items-center gap-1.5"><span className="h-1 w-5 rounded-full bg-[var(--viz-pos)]" /> Positive</span>
              </>
            ) : (
              <>
                <span className="flex items-center gap-1.5"><span className="h-1 w-5 rounded-full bg-[var(--viz-mid)]" /> None</span>
                <span className="flex items-center gap-1.5"><span className="h-1 w-5 rounded-full bg-[var(--viz-pos)]" /> A lot</span>
              </>
            )}
            <span>Thicker is stronger. Click a person to pick them out, or a line for its history.</span>
          </div>
        </Card>
        <div className="space-y-3">
          {focused && (
            <Card className="p-4">
              <p className="font-bold">{focused.name}</p>
              <p className="text-sm text-muted">Mood {focused.mood.toFixed(2)}{focused.circles.length ? ` · ${focused.circles.join(", ")}` : ""}</p>
              {!focused.you && <Link to={`/director/residents/${focused.handle}`} className="mt-1 inline-block text-sm font-semibold text-accent hover:underline">Open in the inspector</Link>}
            </Card>
          )}
          {picked && <EdgePanel key={picked.join("|")} a={picked[0]} b={picked[1]} onClose={() => setPicked(null)} />}
          {!picked && !focused && <p className="px-1 text-sm text-muted">Nothing picked yet.</p>}
        </div>
      </div>
    </div>
  );
}
