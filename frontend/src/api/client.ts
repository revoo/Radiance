/** A failed API call, with the RFC 9457 problem details the backend sent (SPEC §7). */
export class ApiError extends Error {
  readonly status: number;
  readonly detail?: string;

  constructor(status: number, detail?: string) {
    super(detail ?? `request failed: ${status}`);
    this.status = status;
    this.detail = detail;
  }
}

function csrfToken(): string | undefined {
  const match = document.cookie.split("; ").find((c) => c.startsWith("XSRF-TOKEN="));
  return match ? decodeURIComponent(match.slice("XSRF-TOKEN=".length)) : undefined;
}

type Options = { method?: string; json?: unknown; form?: Record<string, string> };

/**
 * Calls the backend through the dev proxy (or the same origin in production). Writes carry the CSRF
 * token from the XSRF-TOKEN cookie; a 204 resolves to undefined.
 */
export async function api<T>(path: string, options: Options = {}): Promise<T> {
  const method = options.method ?? (options.json !== undefined || options.form ? "POST" : "GET");
  const headers: Record<string, string> = { Accept: "application/json" };
  let body: BodyInit | undefined;
  if (options.json !== undefined) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(options.json);
  } else if (options.form) {
    body = new URLSearchParams(options.form);
  }
  if (method !== "GET") {
    const token = csrfToken();
    if (token) headers["X-XSRF-TOKEN"] = token;
  }
  const res = await fetch(path, { method, headers, body, credentials: "same-origin" });
  if (!res.ok) {
    let detail: string | undefined;
    try {
      const problem = (await res.json()) as { detail?: string };
      detail = problem.detail;
    } catch {
      detail = undefined;
    }
    throw new ApiError(res.status, detail);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Makes sure the CSRF cookie exists before the first write (any GET sets it). */
export async function primeCsrf(): Promise<void> {
  if (csrfToken()) return;
  await fetch("/api/me", { credentials: "same-origin" }).catch(() => undefined);
}
