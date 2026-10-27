import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";
import App from "./App.tsx";

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("App", () => {
  it("shows the backend health it reads through the proxy", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(JSON.stringify({ status: "UP" }), { status: 200 }));

    renderApp();

    expect(await screen.findByText("UP")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith("/actuator/health", expect.anything());
  });

  it("says when the backend is unreachable", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("", { status: 502 }));

    renderApp();

    expect(await screen.findByText("unreachable")).toBeInTheDocument();
  });
});
