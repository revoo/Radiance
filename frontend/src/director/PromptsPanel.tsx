import { FileText } from "lucide-react";
import { useState } from "react";
import { useSearchParams } from "react-router";
import { Card, Empty } from "../ui/kit.tsx";
import { type PromptRow, usePrompt, usePrompts } from "./api.ts";
import { field, human, Loading, Pill, Problem, usd, when } from "./ui.tsx";

const PURPOSES = ["render-thread", "render-dm", "live-comment", "appraisal", "vision-appraisal", "headline-classify", "linter-judge", "compaction", "summary", "recap", "genesis"];

function Block({ title, parts }: { title: string; parts: (string | null | undefined)[] }) {
  const shown = parts.filter(Boolean);
  if (shown.length === 0) return null;
  return (
    <section>
      <h3 className="mb-1 text-sm font-bold">{title}</h3>
      {shown.map((p, i) => <pre key={i} className="mb-2 max-h-96 overflow-auto whitespace-pre-wrap break-words rounded-xl bg-panel-2 p-3 text-xs">{p}</pre>)}
    </section>
  );
}

function Detail({ id }: { id: string }) {
  const p = usePrompt(id);
  if (p.isPending) return <Loading what="the prompt" />;
  if (p.error) return <Problem error={p.error} />;
  return (
    <div className="space-y-2">
      <Block title="Cached prefix" parts={p.data.prefix} />
      <Block title="This call" parts={p.data.content} />
      <Block title="Response" parts={[p.data.response]} />
      <Block title="Error" parts={[p.data.error]} />
    </div>
  );
}

function Row({ r, open, onOpen }: { r: PromptRow; open: boolean; onOpen: () => void }) {
  return (
    <button onClick={onOpen} aria-expanded={open} className={`flex w-full flex-wrap items-center gap-2 rounded-xl px-3 py-2 text-left text-sm transition ${open ? "bg-accent-soft" : "hover:bg-panel-2"}`}>
      <span className="font-semibold">{human(r.purpose)}</span>
      <span className="text-muted">{r.alias}</span>
      {r.outcome !== "OK" && <Pill tone="bad">{r.outcome.toLowerCase()}</Pill>}
      <span className="ml-auto flex items-center gap-3 text-xs tabular-nums text-muted">
        {r.costUsd !== null && <span>{usd(r.costUsd)}</span>}
        {r.latencyMs !== null && <span>{(r.latencyMs / 1000).toFixed(1)} s</span>}
        <span>{when(r.at)}</span>
      </span>
    </button>
  );
}

/** The last prompts and what came back, while {@code store-prompts} is on. */
export function PromptsPanel() {
  const [params] = useSearchParams();
  const [purpose, setPurpose] = useState("");
  const [q, setQ] = useState(params.get("q") ?? "");
  const [open, setOpen] = useState<string | null>(null);
  const prompts = usePrompts(purpose, q.trim());
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2">
        <select aria-label="Purpose" value={purpose} onChange={(e) => setPurpose(e.target.value)} className={field}>
          <option value="">Any purpose</option>
          {PURPOSES.map((p) => <option key={p} value={p}>{human(p)}</option>)}
        </select>
        <input aria-label="Search prompts" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Words in the prompt, like @sarah.kim" className={`${field} min-w-52 flex-1`} />
      </div>
      <Problem error={prompts.error} />
      {prompts.isPending && <Loading what="prompts" />}
      {prompts.data?.length === 0 && (
        <Card><Empty icon={<FileText className="size-6" />} title="No prompts stored">They show here while store-prompts is on and calls are being made.</Empty></Card>
      )}
      {prompts.data && prompts.data.length > 0 && (
        <Card className="space-y-0.5 p-2">
          {prompts.data.map((r) => (
            <div key={r.id}>
              <Row r={r} open={open === r.id} onOpen={() => setOpen(open === r.id ? null : r.id)} />
              {open === r.id && <div className="p-3"><Detail id={r.id} /></div>}
            </div>
          ))}
        </Card>
      )}
    </div>
  );
}
