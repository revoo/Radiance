import { Activity, Bell, House, LogOut, MessageCircle, Monitor, Moon, Sun, Users, X } from "lucide-react";
import { type ReactNode, useEffect, useState } from "react";
import { Link, NavLink, useLocation, useNavigate } from "react-router";
import { useLogout, useNotifications, usePeople, useThreads, useWorld } from "../api/queries.ts";
import type { Actor } from "../api/types.ts";
import { live, useLive, worldNow } from "../live/store.ts";
import { Avatar, Card } from "../ui/kit.tsx";

type Theme = "system" | "light" | "dark";

function readTheme(): Theme {
  try {
    const t = localStorage.getItem("theme");
    return t === "light" || t === "dark" ? t : "system";
  } catch {
    return "system";
  }
}

function applyTheme(t: Theme) {
  if (t === "system") document.documentElement.removeAttribute("data-theme");
  else document.documentElement.setAttribute("data-theme", t);
}

export function Logo({ name = "Radiance" }: { name?: string }) {
  return (
    <span className="flex items-center gap-2">
      <span className="bg-brand relative flex size-9 items-center justify-center rounded-xl shadow-[0_6px_18px_-6px_rgb(240_56_107/0.7)]">
        <Sun className="size-5 text-white" aria-hidden="true" />
      </span>
      <span className="text-brand text-xl font-extrabold tracking-tight">{name}</span>
    </span>
  );
}

function ThemeButton() {
  const [theme, setTheme] = useState<Theme>(readTheme);
  useEffect(() => {
    applyTheme(theme);
    try {
      localStorage.setItem("theme", theme);
    } catch {
      // private mode: the theme just doesn't stick
    }
  }, [theme]);
  const next: Record<Theme, Theme> = { system: "light", light: "dark", dark: "system" };
  const Icon = theme === "light" ? Sun : theme === "dark" ? Moon : Monitor;
  return (
    <button
      onClick={() => setTheme(next[theme])}
      aria-label={`Theme: ${theme}. Switch to ${next[theme]}`}
      title={`Theme: ${theme}`}
      className="rounded-full p-2.5 text-muted transition hover:bg-panel-2 hover:text-ink"
    >
      <Icon className="size-5" />
    </button>
  );
}

/** Unread DMs across R's chats, for the Messages badge. */
function useUnreadDms(): number {
  return (useThreads().data ?? []).reduce((n, t) => n + t.unread, 0);
}

function NavIcon({ to, label, icon, badge }: { to: string; label: string; icon: ReactNode; badge?: number }) {
  return (
    <NavLink
      to={to}
      end={to === "/"}
      aria-label={label}
      className={({ isActive }) =>
        `relative flex h-12 w-16 items-center justify-center rounded-xl transition md:w-24 ${
          isActive ? "text-accent" : "text-muted hover:bg-panel-2 hover:text-ink"
        }`
      }
    >
      {({ isActive }) => (
        <>
          {icon}
          {badge ? (
            <span className="animate-pop absolute right-3 top-1.5 min-w-5 rounded-full bg-accent px-1.5 text-center text-[11px] font-bold leading-5 text-white ring-2 ring-panel md:right-7">
              {badge > 99 ? "99+" : badge}
            </span>
          ) : null}
          {isActive && <span className="bg-brand absolute inset-x-3 -bottom-[9px] h-[3px] rounded-full" />}
        </>
      )}
    </NavLink>
  );
}

/** The avatar in the top bar opens R's menu: his profile, the pacing monitor, and logging out. */
function AccountMenu({ me }: { me: Actor }) {
  const [open, setOpen] = useState(false);
  const logout = useLogout();
  const navigate = useNavigate();
  useEffect(() => {
    if (!open) return;
    const close = (e: KeyboardEvent | MouseEvent) => {
      if (e instanceof KeyboardEvent ? e.key === "Escape" : !(e.target as Element).closest("[data-account-menu]")) setOpen(false);
    };
    document.addEventListener("keydown", close);
    document.addEventListener("mousedown", close);
    return () => {
      document.removeEventListener("keydown", close);
      document.removeEventListener("mousedown", close);
    };
  }, [open]);
  const item = "flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-[15px] font-semibold transition hover:bg-panel-2";
  return (
    <div className="relative" data-account-menu>
      <button
        aria-label="Your account"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="flex rounded-full transition active:scale-95"
      >
        <Avatar actor={me} size="sm" ring link={false} />
      </button>
      {open && (
        <div role="menu" aria-label="Your account" className="animate-rise absolute right-0 top-full mt-2 w-64 rounded-2xl border border-line bg-panel p-2 shadow-lift">
          <Link role="menuitem" to={`/people/${me.handle}`} onClick={() => setOpen(false)} className={item}>
            <Avatar actor={me} size="md" link={false} />
            <span className="min-w-0">
              <span className="block truncate">{me.name}</span>
              <span className="block text-xs font-medium text-muted">See your profile</span>
            </span>
          </Link>
          <div className="my-1 border-t border-line" />
          <Link role="menuitem" to="/friends" onClick={() => setOpen(false)} className={item}>
            <span className="flex size-8 items-center justify-center rounded-full bg-panel-2"><Users className="size-4" /></span>
            Friends
          </Link>
          <Link role="menuitem" to="/director/pacer" onClick={() => setOpen(false)} className={item}>
            <span className="flex size-8 items-center justify-center rounded-full bg-panel-2"><Activity className="size-4" /></span>
            Pacing monitor
          </Link>
          <button
            role="menuitem"
            disabled={logout.isPending}
            onClick={() => logout.mutate(undefined, { onSettled: () => navigate("/") })}
            className={item}
          >
            <span className="flex size-8 items-center justify-center rounded-full bg-panel-2"><LogOut className="size-4" /></span>
            {logout.isPending ? "Logging out…" : "Log out"}
          </button>
        </div>
      )}
    </div>
  );
}

function TopBar({ me, network }: { me: Actor; network: string }) {
  const unread = useNotifications().data?.unread ?? 0;
  const dms = useUnreadDms();
  return (
    <header className="fixed inset-x-0 top-0 z-40 h-16 border-b border-line bg-panel/85 backdrop-blur-xl">
      <div className="mx-auto flex h-full max-w-[1320px] items-center justify-between gap-3 px-4">
        <Link to="/" aria-label={`${network} home`}>
          <Logo name={network} />
        </Link>
        <nav aria-label="Main" className="hidden items-center gap-1 sm:flex">
          <NavIcon to="/" label="Feed" icon={<House className="size-6" />} />
          <NavIcon to="/friends" label="Friends" icon={<Users className="size-6" />} />
          <NavIcon to="/messages" label="Messages" icon={<MessageCircle className="size-6" />} badge={dms} />
          <NavIcon to="/notifications" label="Notifications" icon={<Bell className="size-6" />} badge={unread} />
        </nav>
        <div className="flex items-center gap-1">
          <ThemeButton />
          <AccountMenu me={me} />
        </div>
      </div>
    </header>
  );
}

function BottomTabs({ me }: { me: Actor }) {
  const unread = useNotifications().data?.unread ?? 0;
  const dms = useUnreadDms();
  const tab = ({ isActive }: { isActive: boolean }) =>
    `relative flex flex-1 flex-col items-center gap-0.5 py-2 text-[11px] font-semibold ${isActive ? "text-accent" : "text-muted"}`;
  return (
    <nav aria-label="Tabs" className="fixed inset-x-0 bottom-0 z-40 flex border-t border-line bg-panel/90 pb-[env(safe-area-inset-bottom)] backdrop-blur-xl sm:hidden">
      <NavLink to="/" end className={tab}>
        <House className="size-6" aria-hidden="true" /> Feed
      </NavLink>
      <NavLink to="/messages" className={tab}>
        <MessageCircle className="size-6" aria-hidden="true" /> Messages
        {dms > 0 && (
          <span className="absolute right-[calc(50%-22px)] top-1 min-w-4 rounded-full bg-accent px-1 text-[10px] leading-4 text-white">
            {dms}
          </span>
        )}
      </NavLink>
      <NavLink to="/notifications" className={tab}>
        <Bell className="size-6" aria-hidden="true" /> Alerts
        {unread > 0 && (
          <span className="absolute right-[calc(50%-22px)] top-1 min-w-4 rounded-full bg-accent px-1 text-center text-[10px] leading-4 text-white">
            {unread}
          </span>
        )}
      </NavLink>
      <NavLink to={`/people/${me.handle}`} className={tab}>
        <Avatar actor={me} size="xs" link={false} /> You
      </NavLink>
    </nav>
  );
}

function Shortcut({ to, icon, label }: { to: string; icon: ReactNode; label: string }) {
  return (
    <NavLink
      to={to}
      end
      className={({ isActive }) =>
        `flex items-center gap-3 rounded-xl px-3 py-2.5 text-[15px] font-semibold transition ${
          isActive ? "bg-panel text-ink shadow-card" : "text-ink/80 hover:bg-panel"
        }`
      }
    >
      {icon}
      {label}
    </NavLink>
  );
}

function LeftRail({ me }: { me: Actor }) {
  return (
    <aside className="sticky top-20 hidden h-[calc(100vh-6rem)] w-64 shrink-0 flex-col gap-1 lg:flex">
      <Shortcut to={`/people/${me.handle}`} icon={<Avatar actor={me} size="sm" ring link={false} />} label={me.name} />
      <Shortcut
        to="/"
        icon={<span className="bg-brand flex size-8 items-center justify-center rounded-full text-white"><House className="size-4" /></span>}
        label="Feed"
      />
      <Shortcut
        to="/friends"
        icon={<span className="flex size-8 items-center justify-center rounded-full bg-[#1971c2] text-white"><Users className="size-4" /></span>}
        label="Friends"
      />
      <Shortcut
        to="/messages"
        icon={<span className="flex size-8 items-center justify-center rounded-full bg-[#2f9e44] text-white"><MessageCircle className="size-4" /></span>}
        label="Messages"
      />
      <Shortcut
        to="/notifications"
        icon={<span className="flex size-8 items-center justify-center rounded-full bg-[#e8590c] text-white"><Bell className="size-4" /></span>}
        label="Notifications"
      />
      <Shortcut
        to="/director/pacer"
        icon={<span className="flex size-8 items-center justify-center rounded-full bg-[#5f3dc4] text-white"><Activity className="size-4" /></span>}
        label="Pacing monitor"
      />
      <p className="mt-auto px-3 text-xs text-faint">A simulated Chicagoland. Everyone here but you is a resident.</p>
    </aside>
  );
}

/** The world's clock, in its own time zone: it runs 1:1 with real time, plus any offset. */
function WorldClock() {
  const world = useWorld();
  const [now, setNow] = useState(worldNow);
  useEffect(() => {
    const t = setInterval(() => setNow(worldNow()), 15_000);
    return () => clearInterval(t);
  }, []);
  const tz = world.data?.timezone ?? "America/Chicago";
  const hour = Number(new Intl.DateTimeFormat("en-US", { hour: "numeric", hour12: false, timeZone: tz }).format(now));
  const greeting = hour < 5 ? "Late night" : hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : hour < 21 ? "Good evening" : "Night owl hours";
  const Icon = hour >= 6 && hour < 19 ? Sun : Moon;
  return (
    <Card className="bg-brand-soft overflow-hidden p-4">
      <div className="flex items-center justify-between">
        <div>
          <p className="text-xs font-bold uppercase tracking-[0.16em] text-muted">Chicagoland</p>
          <p className="mt-1 text-2xl font-extrabold tabular-nums tracking-tight">
            {new Intl.DateTimeFormat("en-US", { hour: "numeric", minute: "2-digit", timeZone: tz }).format(now)}
          </p>
          <p className="text-sm text-muted">
            {new Intl.DateTimeFormat("en-US", { weekday: "long", month: "short", day: "numeric", timeZone: tz }).format(now)}
          </p>
        </div>
        <span className="relative flex size-14 items-center justify-center">
          <span aria-hidden="true" className="animate-glow bg-brand absolute inset-0 rounded-full blur-md" />
          <span className="relative flex size-12 items-center justify-center rounded-full bg-panel text-accent">
            <Icon className="size-6" aria-hidden="true" />
          </span>
        </span>
      </div>
      <p className="mt-3 text-sm font-semibold">{greeting}.</p>
    </Card>
  );
}

function RightRail() {
  const friends = usePeople("friends");
  return (
    <aside className="sticky top-20 hidden h-[calc(100vh-6rem)] w-72 shrink-0 flex-col gap-4 xl:flex">
      <WorldClock />
      <div className="min-h-0 flex-1 overflow-y-auto">
        <h2 className="px-2 pb-2 text-sm font-bold text-muted">Contacts</h2>
        <ul>
          {(friends.data ?? []).map((a) => (
            <li key={a.id}>
              <Link to={`/people/${a.handle}`} className="flex items-center gap-3 rounded-xl px-2 py-1.5 transition hover:bg-panel">
                <Avatar actor={a} size="sm" link={false} />
                <span className="truncate text-[15px] font-medium">{a.name}</span>
              </Link>
            </li>
          ))}
        </ul>
      </div>
    </aside>
  );
}

function Toasts() {
  const toasts = useLive((s) => s.toasts);
  const navigate = useNavigate();
  return (
    <div aria-live="polite" className="pointer-events-none fixed bottom-20 left-4 z-50 flex flex-col gap-2 sm:bottom-6">
      {toasts.map((t) => (
        <div key={t.id} className="animate-toast pointer-events-auto flex max-w-sm items-center gap-3 rounded-2xl border border-line bg-panel py-2.5 pl-3 pr-2 shadow-lift">
          <span className="bg-brand flex size-9 shrink-0 items-center justify-center rounded-full text-white">
            <Bell className="size-4" aria-hidden="true" />
          </span>
          <button
            className="min-w-0 text-left text-sm font-medium"
            onClick={() => {
              live.dismiss(t.id);
              if (t.to) navigate(t.to);
              else if (t.postId) navigate(`/posts/${t.postId}`);
            }}
          >
            {t.text}
          </button>
          <button aria-label="Dismiss" onClick={() => live.dismiss(t.id)} className="rounded-full p-1 text-muted hover:bg-panel-2">
            <X className="size-4" />
          </button>
        </div>
      ))}
    </div>
  );
}

/** Only when no script covered the present: history streams in while R plays (SPEC §5.1). Never blocks. */
function BackfillBanner() {
  const streaming = useLive((s) => s.backfilling);
  const world = useWorld();
  const backfilling = streaming || Boolean(world.data?.backfilling);
  if (!backfilling) return null;
  return (
    <div role="status" className="animate-rise flex items-center gap-3 rounded-2xl border border-line bg-panel px-4 py-3 text-sm shadow-card">
      <span className="relative flex size-3">
        <span className="absolute inline-flex size-full animate-ping rounded-full bg-accent/60" />
        <span className="relative inline-flex size-3 rounded-full bg-accent" />
      </span>
      <span>
        <span className="font-semibold">Catching the world up.</span>{" "}
        <span className="text-muted">Recent days are filling in as they're written; go ahead and post.</span>
      </span>
    </div>
  );
}

export function Layout({ me, network, children }: { me: Actor; network: string; children: ReactNode }) {
  const wide = useLocation().pathname.startsWith("/messages");
  return (
    <div className="min-h-screen pb-20 pt-16 sm:pb-8">
      <TopBar me={me} network={network} />
      <div className="mx-auto flex max-w-[1320px] gap-6 px-3 pt-4 sm:px-4">
        <LeftRail me={me} />
        <main className={`mx-auto w-full min-w-0 flex-1 space-y-4 ${wide ? "max-w-[980px]" : "max-w-[640px]"}`}>
          <BackfillBanner />
          {children}
        </main>
        {!wide && <RightRail />}
      </div>
      <BottomTabs me={me} />
      <Toasts />
    </div>
  );
}
