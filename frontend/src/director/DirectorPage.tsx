import { Activity, BookOpen, Clapperboard, Clock, Coins, FileText, Moon, Network, ScrollText, SlidersHorizontal, UserRound } from "lucide-react";
import type { ReactNode } from "react";
import { NavLink, useParams } from "react-router";
import { PacerPage } from "../pages/PacerPage.tsx";
import { useTime } from "./api.ts";
import { BatchPanel, CostsPanel } from "./BatchCosts.tsx";
import { GraphPanel } from "./GraphPanel.tsx";
import { KnobsPanel } from "./KnobsPanel.tsx";
import { LogPanel } from "./LogPanel.tsx";
import { PromptsPanel } from "./PromptsPanel.tsx";
import { Residents } from "./Residents.tsx";
import { Storylines } from "./Storylines.tsx";
import { TimePanel } from "./TimePanel.tsx";
import { Pill, when } from "./ui.tsx";

const TABS: { id: string; label: string; icon: ReactNode; panel: () => ReactNode }[] = [
  { id: "time", label: "Time & incidents", icon: <Clock className="size-4" />, panel: () => <TimePanel /> },
  { id: "storylines", label: "Storylines", icon: <BookOpen className="size-4" />, panel: () => <Storylines /> },
  { id: "graph", label: "Graph", icon: <Network className="size-4" />, panel: () => <GraphPanel /> },
  { id: "residents", label: "Residents", icon: <UserRound className="size-4" />, panel: () => <Residents /> },
  { id: "log", label: "Log", icon: <ScrollText className="size-4" />, panel: () => <LogPanel /> },
  { id: "batch", label: "Batch", icon: <Moon className="size-4" />, panel: () => <BatchPanel /> },
  { id: "pacer", label: "Pacing", icon: <Activity className="size-4" />, panel: () => <PacerPage /> },
  { id: "costs", label: "Costs", icon: <Coins className="size-4" />, panel: () => <CostsPanel /> },
  { id: "knobs", label: "Knobs", icon: <SlidersHorizontal className="size-4" />, panel: () => <KnobsPanel /> },
  { id: "prompts", label: "Prompts", icon: <FileText className="size-4" />, panel: () => <PromptsPanel /> },
];

function Now() {
  const t = useTime().data;
  if (!t) return null;
  return (
    <p className="flex items-center gap-2 text-sm tabular-nums text-muted">
      {when(t.worldTime)} {t.paused && <Pill tone="accent">paused</Pill>}
    </p>
  );
}

/** The Director's console (SPEC §5.16): what's under the world, and the levers on it. */
export function DirectorPage() {
  const { tab } = useParams();
  const current = TABS.find((t) => t.id === tab) ?? TABS[0]!;
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2 px-1">
        <h1 className="flex items-center gap-2 text-2xl font-extrabold tracking-tight">
          <Clapperboard className="size-6 text-accent" aria-hidden="true" /> Director's console
        </h1>
        <Now />
      </div>
      <nav aria-label="Console" className="-mx-1 flex gap-1 overflow-x-auto px-1 pb-1 [scrollbar-width:thin]">
        {TABS.map((t) => (
          <NavLink
            key={t.id}
            to={`/director/${t.id}`}
            aria-current={t.id === current.id ? "page" : undefined}
            className={`flex shrink-0 items-center gap-1.5 rounded-xl px-3 py-2 text-sm font-semibold transition ${
              t.id === current.id ? "bg-panel text-ink shadow-card" : "text-muted hover:bg-panel hover:text-ink"
            }`}
          >
            {t.icon} {t.label}
          </NavLink>
        ))}
      </nav>
      {current.panel()}
    </div>
  );
}
