import { type FormEvent, useState } from "react";
import { primeCsrf } from "../api/client.ts";
import { useLogin } from "../api/queries.ts";
import { Logo } from "../shell/Layout.tsx";
import { Button } from "../ui/kit.tsx";

/** Sign in (SPEC §5.17): a single local user; the backend sets a session cookie. */
export function LoginPage({ network = "Radiance" }: { network?: string }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const login = useLogin();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    await primeCsrf();
    login.mutate({ username, password });
  };

  const error = login.error as { status?: number; detail?: string } | null;
  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden px-4">
      <div aria-hidden="true" className="pointer-events-none absolute inset-0">
        <div className="animate-glow absolute -left-24 -top-24 size-[420px] rounded-full bg-[#ff8a00]/30 blur-3xl" />
        <div className="animate-glow absolute -bottom-32 right-[-80px] size-[460px] rounded-full bg-[#7c4dff]/30 blur-3xl [animation-delay:0.8s]" />
        <div className="animate-glow absolute left-1/2 top-1/3 size-[300px] -translate-x-1/2 rounded-full bg-[#ff3d77]/25 blur-3xl [animation-delay:1.6s]" />
      </div>
      <div className="relative grid w-full max-w-4xl items-center gap-10 md:grid-cols-2">
        <div className="text-center md:text-left">
          <div className="flex justify-center md:justify-start">
            <Logo name={network} />
          </div>
          <h1 className="mt-6 text-4xl font-extrabold leading-[1.05] tracking-tight md:text-5xl">
            Thirty neighbors.
            <br />
            <span className="text-brand">One of them is you.</span>
          </h1>
          <p className="mt-4 text-lg text-muted">
            Chicagoland is already talking. Drama, crushes, hot takes and deep-dish debates — catch up and jump in.
          </p>
        </div>
        <form onSubmit={submit} className="rounded-3xl border border-line bg-panel/80 p-6 shadow-lift backdrop-blur-xl">
          <h2 className="text-xl font-bold">Welcome back</h2>
          <label className="mt-5 block text-sm font-semibold" htmlFor="username">
            Username
          </label>
          <input
            id="username"
            autoComplete="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            className="mt-1.5 w-full rounded-xl border border-line bg-panel-2 px-4 py-3 outline-none transition focus:border-accent focus:bg-panel"
          />
          <label className="mt-4 block text-sm font-semibold" htmlFor="password">
            Password
          </label>
          <input
            id="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="mt-1.5 w-full rounded-xl border border-line bg-panel-2 px-4 py-3 outline-none transition focus:border-accent focus:bg-panel"
          />
          {error && (
            <p role="alert" className="mt-3 text-sm font-medium text-bad">
              {error.status === 429 ? (error.detail ?? "Too many attempts. Try again in a few minutes.") : "Wrong username or password."}
            </p>
          )}
          <Button type="submit" busy={login.isPending} disabled={!username || !password} className="mt-6 w-full py-3 text-base">
            Log in
          </Button>
        </form>
      </div>
    </div>
  );
}
