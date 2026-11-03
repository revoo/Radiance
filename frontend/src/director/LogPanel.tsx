import { useInfiniteQuery } from "@tanstack/react-query";
import { CircleCheck, ScrollText } from "lucide-react";
import { useState } from "react";
import { api } from "../api/client.ts";
import { Avatar, Button, Card, Empty, Segmented } from "../ui/kit.tsx";
import { type LogEntry, logUrl, useInjectables, useReports } from "./api.ts";
import { field, human, Loading, Pill, Problem, Section, when } from "./ui.tsx";

const SOURCES = ["STORYTELLER", "ARC", "ROUTINE", "NEWS", "CALENDAR", "USER", "DIRECTOR"];
const PAGE = 60;

function Line({ e }: { e: LogEntry }) {
  return (
    <li className="flex items-start gap-3 py-2.5">
      {e.actor ? <Avatar actor={e.actor} size="xs" /> : <span className="size-6 shrink-0" />}
      <div className="min-w-0 flex-1 text-sm">
        <p>
          <span className="font-semibold">{e.actor?.name ?? "The world"}</span>
          {e.target && <span className="text-muted"> → {e.target.name}</span>}
          <span className="text-muted"> · {human(e.type).toLowerCase()}</span>
        </p>
        {(e.recapLine ?? e.text) && <p className="text-muted">{e.recapLine ?? e.text}</p>}
        <p className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-muted">
          <span className="tabular-nums">{when(e.at)}</span>
          <Pill>{e.source.toLowerCase()}</Pill>
          {e.status !== "AIRED" && <Pill tone={e.status === "DROPPED" ? "bad" : "violet"}>{e.status.toLowerCase()}</Pill>}
          {e.visibility !== "PUBLIC" && <Pill>{e.visibility.toLowerCase()}</Pill>}
          <span className="tabular-nums" title="Importance">★ {e.importance}</span>
        </p>
      </div>
    </li>
  );
}

function Events() {
  const [source, setSource] = useState("");
  const [status, setStatus] = useState("");
  const [actor, setActor] = useState("");
  const [q, setQ] = useState("");
  const people = useInjectables();
  const log = useInfiniteQuery({
    queryKey: ["director", "log", source, status, actor, q],
    initialPageParam: undefined as number | undefined,
    queryFn: ({ pageParam }) => api<LogEntry[]>(logUrl({ source, status, actor, q, before: pageParam })),
    getNextPageParam: (last) => (last.length < PAGE ? undefined : last[last.length - 1]!.at),
  });
  const rows = log.data?.pages.flat() ?? [];
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2">
        <select aria-label="Source" value={source} onChange={(e) => setSource(e.target.value)} className={field}>
          <option value="">Any source</option>
          {SOURCES.map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}
        </select>
        <select aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value)} className={field}>
          <option value="">Any status</option>
          <option value="AIRED">aired</option>
          <option value="SCRIPTED">scripted</option>
          <option value="DROPPED">dropped</option>
        </select>
        <select aria-label="Who" value={actor} onChange={(e) => setActor(e.target.value)} className={field}>
          <option value="">Anyone</option>
          {people.data?.residents.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
        <input aria-label="Search the log" value={q} onChange={(e) => setQ(e.target.value)} placeholder="A word in the event" className={`${field} min-w-40 flex-1`} />
      </div>
      <Problem error={log.error} />
      {log.isPending && <Loading what="the log" />}
      {!log.isPending && rows.length === 0 && <Card><Empty icon={<ScrollText className="size-6" />} title="Nothing matches">Try fewer filters.</Empty></Card>}
      {rows.length > 0 && <Card className="px-4"><ul className="divide-y divide-line">{rows.map((e) => <Line key={e.id} e={e} />)}</ul></Card>}
      {log.hasNextPage && <div className="text-center"><Button variant="soft" busy={log.isFetchingNextPage} onClick={() => void log.fetchNextPage()}>Older</Button></div>}
    </div>
  );
}

function Report() {
  const reports = useReports();
  if (reports.isPending) return <Loading what="the reports" />;
  if (reports.error) return <Problem error={reports.error} />;
  const { continuity, drops, droppedThisWeek } = reports.data;
  return (
    <div className="space-y-3">
      <Section title="Continuity report" hint="What the auditor found when the last batches wrote their scripts.">
        {continuity.length === 0 && <p className="flex items-center gap-2 text-sm text-muted"><CircleCheck className="size-4 text-good" aria-hidden="true" /> No findings in the recent runs.</p>}
        <ul className="space-y-2 text-sm">
          {continuity.map((r) => (
            <li key={r.id}>
              <span className="font-semibold">{when(r.startedAt)}</span>
              <ul className="mt-1 list-disc space-y-0.5 pl-5 text-muted">
                {r.notes.filter((n) => n.includes("continuity")).map((n) => <li key={n} className="break-words">{n}</li>)}
              </ul>
            </li>
          ))}
        </ul>
      </Section>
      <Section title="Airing report" hint={`Items airing refused, and why. ${droppedThisWeek} posts and comments dropped in the last week.`}>
        {drops.length === 0 && <p className="text-sm text-muted">Nothing dropped since the server started.</p>}
        <ul className="divide-y divide-line text-sm">
          {drops.map((d, i) => (
            <li key={`${d.at}-${i}`} className="flex items-start gap-3 py-1.5">
              <span className="shrink-0 tabular-nums text-muted">{when(d.at)}</span>
              <span className="min-w-0 break-words">{d.reason}</span>
            </li>
          ))}
        </ul>
      </Section>
    </div>
  );
}

/** The event log with its filters, and the two reports on how the script is holding together. */
export function LogPanel() {
  const [view, setView] = useState<"events" | "reports">("events");
  return (
    <div className="space-y-3">
      <Segmented label="View" value={view} onChange={setView} options={[{ value: "events", label: "Event log" }, { value: "reports", label: "Reports" }]} />
      {view === "events" ? <Events /> : <Report />}
    </div>
  );
}
