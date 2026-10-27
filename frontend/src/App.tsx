import { useQuery } from "@tanstack/react-query";
import { Sun } from "lucide-react";
import { Route, Routes } from "react-router";
import { fetchHealth } from "./api/health.ts";

function BackendStatus() {
  const health = useQuery({ queryKey: ["health"], queryFn: fetchHealth, retry: false });
  const label = health.isPending ? "checking…" : health.isError ? "unreachable" : health.data.status;
  return (
    <p className="text-sm text-muted">
      Backend: <span data-testid="backend-status" className="font-semibold text-ink">{label}</span>
    </p>
  );
}

function Home() {
  return (
    <main className="mx-auto flex min-h-screen max-w-xl flex-col items-center justify-center gap-4 px-4">
      <div className="flex items-center gap-2 text-accent">
        <Sun aria-hidden="true" className="size-8" />
        <h1 className="text-3xl font-bold">Radiance</h1>
      </div>
      <div className="rounded-lg bg-panel px-6 py-4 shadow-sm">
        <BackendStatus />
      </div>
    </main>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path="*" element={<Home />} />
    </Routes>
  );
}
