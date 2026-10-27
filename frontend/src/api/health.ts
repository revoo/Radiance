export type Health = { status: string };

/** Reads the backend's health through the dev proxy (or the same origin in production). */
export async function fetchHealth(): Promise<Health> {
  const res = await fetch("/actuator/health", { headers: { Accept: "application/json" } });
  if (!res.ok) throw new Error(`health check failed: ${res.status}`);
  return (await res.json()) as Health;
}
