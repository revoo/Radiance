import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client.ts";

export type Who = { id: string; handle: string; name: string; you: boolean; avatarUrl: string | null };

export type ArcView = {
  id: string; defId: string; description: string | null; status: "ACTIVE" | "RESOLVED" | "ABANDONED"; phase: string; phases: string[];
  visibility: string; startedAt: number; phaseEnteredAt: number; nextTransitionAt: number | null; lastBeatAt: number | null;
  endedAt: number | null; summary: string; sensitive: boolean; people: Who[]; knowers: Who[]; constraints: string[];
};

export type LogEntry = {
  id: string; at: number; type: string; defId: string | null; actor: Who | null; target: Who | null; arcId: string | null;
  importance: number; visibility: string; source: string; status: string; text: string | null; recapLine: string | null;
};

export type GraphNode = { id: string; handle: string; name: string; you: boolean; avatarUrl: string | null; gender: string | null; mood: number; circles: string[] };
export type GraphEdge = { from: string; to: string; kind: string; affinity: number; trust: number; attraction: number; familiarity: number; respect: number; connected: boolean; tags: string[] };
export type Graph = { nodes: GraphNode[]; edges: GraphEdge[] };
export type EdgeHistory = { from: Who; to: Who; there: GraphEdge | null; back: GraphEdge | null; summary: string | null; events: LogEntry[] };

export type Inspector = {
  who: Who; occupation: string | null; livesIn: string | null; relationshipStatus: string | null; bio: string | null; backstory: string; lately: string | null;
  traits: Record<string, number>; traitOffsets: Record<string, number>; temperament: unknown; quirks: string[]; interests: Record<string, number>; politics: unknown; voice: unknown; routine: unknown;
  mood: number; energy: number; stress: number; partnerSatisfaction: number | null; location: string; constraints: string[];
  entities: { id: string; kind: string; name: string; status: string; description: string | null }[];
  memories: { id: string; at: number; kind: string; text: string; importance: number; score: number }[];
  impressionOfR: GraphEdge | null; impressionSummary: string | null;
  history: { day: string; affinity: number; mood: number | null }[];
};

export type Run = {
  id: string; kind: string; status: string; startedAt: number; finishedAt: number | null; scriptFrom: number | null; scriptTo: number | null; fidelity: string | null;
  planned: number | null; rendered: number | null; dropped: number | null; costUsd: number | null; rows: number | null; error: string | null; notes: string[];
};
export type Mix = { key: string; target: number; actual: number };
export type BatchView = {
  running: boolean; scriptedUntil: number; runs: Run[]; eventMix: Mix[]; regions: Mix[];
  upcoming: { posts: number; comments: number; reactions: number; dms: number; events: LogEntry[] };
};
export type Reports = { continuity: Run[]; drops: { at: number; reason: string }[]; droppedThisWeek: number };

export type Slice = { key: string; usd: number; calls: number };
export type BudgetWindow = { id: string; used: number; limit: number; resetsAt: number; closedUntil: number | null };
export type Costs = {
  today: number; month: number; byPurpose: Slice[]; byProvider: Slice[]; byModel: Slice[]; perBatch: Slice[]; cacheHitRate: number; fallbackRate: number; calls: number;
  windows: BudgetWindow[]; parked: { reason: string | null; jobs: number; resumes: number }[];
};

export type PromptRow = { id: string; at: number; purpose: string; alias: string; model: string; outcome: string; costUsd: number | null; latencyMs: number | null };
export type PromptFile = { callId: string; purpose: string; alias: string; model: string; prefix: string[]; content: string[]; outcome: string; response: string | null; error: string | null };

export type Time = { worldTime: number; paused: boolean; offsetMs: number; committedUntil: number; scriptedUntil: number; backfilling: boolean };

export type Injectables = {
  events: { id: string; category: string; description: string | null; importance: number; otherRole: string | null }[];
  arcs: { id: string; category: string; description: string | null; blacklisted: boolean; needsOther: boolean; sensitive: boolean }[];
  residents: Who[];
  couples: Who[][];
};
export type Injection = { kind: "EVENT" | "ARC" | "BREAKUP"; defId?: string; actor: string; other?: string };
export type Injected = { events: number; posts: number; comments: number; reactions: number; firstAt: number | null };

export type Range = { min: number; max: number; whole: boolean };
export type Route = { model: string; fallback: string[] };
export type ModelView = { alias: string; provider: string; available: boolean; vision: boolean; inputPrice: number; outputPrice: number };
export type Knobs = Record<string, number | string | boolean | Record<string, number>>;
export type Settings = {
  knobs: Knobs; knobDefaults: Knobs; ranges: Record<string, Range>; profiles: string[]; blacklist: { id: string; locked: boolean }[]; arcs: string[];
  routes: Record<string, Route>; models: ModelView[]; budgets: { dailyUsd: number; perBatchUsd: number; monthlyUsd: number }; imagesPerDay: number; pacing: unknown;
};

const D = "/api/director";

export const useArcs = () => useQuery({ queryKey: ["director", "arcs"], queryFn: () => api<ArcView[]>(`${D}/arcs`) });
export const useArcEvents = (id: string, on: boolean) =>
  useQuery({ queryKey: ["director", "arc", id], queryFn: () => api<LogEntry[]>(`${D}/arcs/${id}/events`), enabled: on });
export const useGraph = () => useQuery({ queryKey: ["director", "graph"], queryFn: () => api<Graph>(`${D}/graph`) });
export const useEdge = (from: string | null, to: string | null) =>
  useQuery({ queryKey: ["director", "edge", from, to], queryFn: () => api<EdgeHistory>(`${D}/graph/edge?from=${from}&to=${to}`), enabled: Boolean(from && to) });
export const useInspector = (handle: string | undefined) =>
  useQuery({ queryKey: ["director", "resident", handle], queryFn: () => api<Inspector>(`${D}/residents/${handle}`), enabled: Boolean(handle) });
export const useReports = () => useQuery({ queryKey: ["director", "reports"], queryFn: () => api<Reports>(`${D}/reports`) });
export const useBatch = () =>
  useQuery({ queryKey: ["director", "batch"], queryFn: () => api<BatchView>(`${D}/batch`), refetchInterval: (q) => (q.state.data?.running ? 2_000 : 30_000) });
export const useCosts = () => useQuery({ queryKey: ["director", "costs"], queryFn: () => api<Costs>(`${D}/costs`), refetchInterval: 30_000 });
export const useSettings = () => useQuery({ queryKey: ["director", "settings"], queryFn: () => api<Settings>(`${D}/settings`) });
export const useTime = () => useQuery({ queryKey: ["director", "time"], queryFn: () => api<Time>(`${D}/time`), refetchInterval: 5_000 });
export const useInjectables = () => useQuery({ queryKey: ["director", "injectables"], queryFn: () => api<Injectables>(`${D}/injectables`) });
export const usePrompts = (purpose: string, q: string) =>
  useQuery({
    queryKey: ["director", "prompts", purpose, q],
    queryFn: () => api<PromptRow[]>(`${D}/prompts?${new URLSearchParams({ ...(purpose ? { purpose } : {}), ...(q ? { q } : {}) })}`),
  });
export const usePrompt = (id: string | null) =>
  useQuery({ queryKey: ["director", "prompt", id], queryFn: () => api<PromptFile>(`${D}/prompts/${id}`), enabled: Boolean(id) });

export function logUrl(f: { source?: string; status?: string; actor?: string; q?: string; before?: number }): string {
  const p = new URLSearchParams();
  Object.entries(f).forEach(([k, v]) => {
    if (v !== undefined && v !== "") p.set(k, String(v));
  });
  const s = p.toString();
  return `${D}/events${s ? `?${s}` : ""}`;
}

export function useSaveSettings() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (patch: Record<string, unknown>) => api<Settings>(`${D}/settings`, { method: "PUT", json: patch }),
    onSuccess: (s) => {
      qc.setQueryData(["director", "settings"], s);
      void qc.invalidateQueries({ queryKey: ["director", "injectables"] });
    },
  });
}

/** Time moves the whole world: everything on screen may be out of date. */
export function useTimeAction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (a: { action: "pause" | "resume" } | { action: "fast-forward"; hours: number }) =>
      api<Time>(`${D}/time/${a.action}${a.action === "fast-forward" ? `?hours=${a.hours}` : ""}`, { method: "POST" }),
    onSuccess: () => void qc.invalidateQueries(),
  });
}

export function useInject() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (i: Injection) => api<Injected>(`${D}/inject`, { method: "POST", json: i }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["director"] }),
  });
}

export function useRunBatch() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api<void>(`${D}/batch/run`, { method: "POST" }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["director", "batch"] }),
  });
}
