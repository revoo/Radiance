import { Flame, HeartCrack, MessagesSquare, Sparkles, Wind } from "lucide-react";
import { type ReactNode, useEffect, useState } from "react";
import { Link } from "react-router";
import { useRail, useWorld } from "../api/queries.ts";
import type { Rail, TalkedStory } from "../api/types.ts";
import { worldNow } from "../live/store.ts";
import { Avatar } from "../ui/kit.tsx";
import { isDaytime, skyGradient, skyIcon, skyOf, type Sky, weatherQuip } from "./weatherLook.ts";

const FX_COUNT: Partial<Record<Sky, number>> = { drizzle: 8, rain: 16, storm: 16, snow: 14 };

/** Rain, snow or stars falling behind the card's text; hidden for people who ask for less motion. */
function SkyFx({ sky, day }: { sky: Sky; day: boolean }) {
  const count = FX_COUNT[sky] ?? (sky === "clear" && !day ? 12 : 0);
  if (count === 0) return null;
  const kind = sky === "snow" ? "flake" : sky === "clear" ? "star" : "drop";
  return (
    <div aria-hidden="true" className="weather-fx pointer-events-none absolute inset-0 overflow-hidden">
      {sky === "storm" && <span className="weather-flash absolute inset-0" />}
      {Array.from({ length: count }, (_, i) => (
        <span
          key={i}
          className={`weather-${kind} absolute`}
          style={{
            // spread evenly-ish, the same on every render
            left: `${(i * 61 + 7) % 100}%`,
            top: kind === "star" ? `${(i * 37 + 5) % 70}%` : undefined,
            animationDelay: `${((i * 0.37) % 2).toFixed(2)}s`,
            animationDuration: kind === "drop" ? `${0.7 + (i % 4) * 0.12}s` : kind === "flake" ? `${3 + (i % 4)}s` : `${2 + (i % 3)}s`,
          }}
        />
      ))}
    </div>
  );
}

function useLocalHour() {
  const tz = useWorld().data?.timezone ?? "America/Chicago";
  return (ms: number) => Number(new Intl.DateTimeFormat("en-US", { hour: "numeric", hour12: false, timeZone: tz }).format(ms)) % 24;
}

function hourLabel(hour: number): string {
  if (hour === 0) return "12a";
  if (hour === 12) return "12p";
  return hour < 12 ? `${hour}a` : `${hour - 12}p`;
}

/** What time it is in the world, kept current. */
function WorldTime() {
  const tz = useWorld().data?.timezone ?? "America/Chicago";
  const [now, setNow] = useState(worldNow);
  useEffect(() => {
    const t = setInterval(() => setNow(worldNow()), 15_000);
    return () => clearInterval(t);
  }, []);
  return (
    <p className="mb-2.5 flex items-baseline justify-between gap-2 text-[11px] font-bold uppercase tracking-[0.14em] text-white/80">
      <span>Chicagoland</span>
      <span className="tabular-nums">
        {new Intl.DateTimeFormat("en-US", { weekday: "short", hour: "numeric", minute: "2-digit", timeZone: tz }).format(now)}
      </span>
    </p>
  );
}

/** The world right now: the clock, its sky, its numbers, the next few hours, and an opinion. */
export function WeatherCard() {
  const weather = useRail().data?.weather;
  const localHour = useLocalHour();
  if (!weather) return null;
  const sky = skyOf(weather.wmo);
  const day = isDaytime(localHour(weather.hour));
  const Icon = skyIcon(sky, day);
  return (
    <section
      aria-label="Weather"
      title={weather.description}
      className="relative shrink-0 overflow-hidden rounded-2xl p-4 text-white shadow-lg"
      style={{ background: skyGradient(sky, day) }}
    >
      <SkyFx sky={sky} day={day} />
      <div className="relative">
        <WorldTime />
        <div className="flex items-start justify-between gap-2">
          <div>
            <p className="text-[44px] font-extrabold leading-none tracking-tighter tabular-nums">{weather.tempF}°</p>
            <p className="mt-1.5 text-sm font-semibold capitalize">{weather.sky}</p>
          </div>
          <Icon className="weather-icon size-14 drop-shadow-lg" strokeWidth={1.5} aria-hidden="true" />
        </div>
        <p className="mt-1 text-[13px] italic text-white/85">{weatherQuip(sky, weather.tempF, weather.feelsLikeF)}</p>
        <div className="mt-2.5 flex flex-wrap gap-x-3 gap-y-0.5 text-xs font-medium tabular-nums text-white/80">
          <span>Feels {weather.feelsLikeF}°</span>
          <span>
            H {weather.highF}° · L {weather.lowF}°
          </span>
          <span className="inline-flex items-center gap-1">
            <Wind className="size-3" aria-hidden="true" />
            {weather.windMph} mph
          </span>
        </div>
        {weather.next.length > 0 && (
          <ol className="mt-3 grid grid-cols-4 gap-1 rounded-xl bg-black/20 p-1.5 backdrop-blur-sm">
            {weather.next.map((h) => {
              const hour = localHour(h.hour);
              const NextIcon = skyIcon(skyOf(h.wmo), isDaytime(hour));
              return (
                <li key={h.hour} className="flex flex-col items-center gap-0.5 text-[11px] font-semibold tabular-nums">
                  <span className="text-white/70">{hourLabel(hour)}</span>
                  <NextIcon className="size-4" aria-hidden="true" />
                  <span>{h.tempF}°</span>
                </li>
              );
            })}
          </ol>
        )}
      </div>
    </section>
  );
}

/** The compact version, above the feed on screens with no rail. */
export function WeatherChip() {
  const weather = useRail().data?.weather;
  const localHour = useLocalHour();
  if (!weather) return null;
  const sky = skyOf(weather.wmo);
  const day = isDaytime(localHour(weather.hour));
  const Icon = skyIcon(sky, day);
  return (
    <p
      title={weather.description}
      className="inline-flex items-center gap-2 rounded-full px-3.5 py-1.5 text-sm font-bold tabular-nums text-white shadow"
      style={{ background: skyGradient(sky, day) }}
    >
      <Icon className="size-4" aria-hidden="true" />
      {weather.tempF}°F · {weather.sky}
    </p>
  );
}

type Mood = { label: string; icon: ReactNode; chip: string; hero: string; bar: string };

/** How a story feels right now, from what it's about and how much people are talking. */
export function storyMood(t: TalkedStory): Mood {
  const talk = t.posts + t.comments;
  if (t.sensitivity === "TRAGIC") {
    return {
      label: "Heavy",
      icon: <HeartCrack className="size-3" aria-hidden="true" />,
      chip: "bg-[#475569]/25 text-[#94a3b8]",
      hero: "linear-gradient(145deg, #1e293b, #475569)",
      bar: "#94a3b8",
    };
  }
  if (t.controversy >= 0.6) {
    return {
      label: talk >= 3 ? "Heated" : "Touchy",
      icon: <Flame className="size-3" aria-hidden="true" />,
      chip: "bg-[#f03e3e]/15 text-[#ff6b6b]",
      hero: "linear-gradient(145deg, #7f1d1d, #c2410c)",
      bar: "#ff6b6b",
    };
  }
  if (talk >= 3) {
    return {
      label: "Buzzing",
      icon: <MessagesSquare className="size-3" aria-hidden="true" />,
      chip: "bg-[#845ef7]/15 text-[#a78bfa]",
      hero: "linear-gradient(145deg, #4c1d95, #7c3aed)",
      bar: "#a78bfa",
    };
  }
  return {
    label: "New",
    icon: <Sparkles className="size-3" aria-hidden="true" />,
    chip: "bg-[#22b8cf]/15 text-[#22b8cf]",
    hero: "linear-gradient(145deg, #155e75, #0e7490)",
    bar: "#22b8cf",
  };
}

const REGION: Record<string, string> = { LOCAL: "Chicago", NATIONAL: "US", WORLD: "World" };

function Faces({ story }: { story: TalkedStory }) {
  const first = story.people[0];
  if (!first) return null;
  const others = story.posts - 1;
  return (
    <span className="flex min-w-0 items-center gap-1.5">
      <span className="flex shrink-0 -space-x-1.5">
        {story.people.map((p) => (
          <Avatar key={p.id} actor={p} size="xs" link={false} />
        ))}
      </span>
      <span className="truncate text-xs">
        {first.name.split(" ")[0]}
        {others > 0 && ` +${others}`}
        {story.comments > 0 && ` · ${story.comments} ${story.comments === 1 ? "reply" : "replies"}`}
      </span>
    </span>
  );
}

function Hero({ story }: { story: TalkedStory }) {
  const mood = storyMood(story);
  return (
    <Link
      to={`/posts/${story.postId}`}
      className="block rounded-xl p-3 text-white transition hover:-translate-y-0.5 hover:shadow-lg"
      style={{ background: mood.hero }}
    >
      <span className="flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-wider text-white/85">
        {mood.icon}
        {mood.label}
        {REGION[story.region] && <span className="font-semibold text-white/65">· {REGION[story.region]}</span>}
      </span>
      <span className="mt-1.5 line-clamp-3 block text-[15px] font-bold leading-snug">{story.title}</span>
      <span className="mt-2 flex items-center justify-between gap-2 text-white/90">
        <Faces story={story} />
        <span className="shrink-0 text-[11px] text-white/65">{story.source}</span>
      </span>
    </Link>
  );
}

function Row({ story, rank, top }: { story: TalkedStory; rank: number; top: number }) {
  const mood = storyMood(story);
  const talk = story.posts + story.comments;
  return (
    <li>
      <Link to={`/posts/${story.postId}`} className="group flex gap-2.5 rounded-xl px-1.5 py-2 transition hover:bg-panel">
        <span aria-hidden="true" className="w-4 shrink-0 text-center text-lg font-extrabold leading-tight text-muted/50 tabular-nums">
          {rank}
        </span>
        <span className="min-w-0 flex-1">
          <span className="line-clamp-2 text-[13px] font-semibold leading-snug group-hover:underline">{story.title}</span>
          <span className="mt-1 flex items-center gap-1.5">
            <span className={`inline-flex shrink-0 items-center gap-1 rounded-full px-1.5 py-px text-[10px] font-bold ${mood.chip}`}>
              {mood.icon}
              {mood.label}
            </span>
            <span className="min-w-0 text-muted">
              <Faces story={story} />
            </span>
          </span>
          <span aria-hidden="true" className="mt-1.5 block h-1 overflow-hidden rounded-full bg-panel-2">
            <span
              className="block h-full rounded-full transition-[width] duration-700"
              style={{ width: `${Math.max(8, (talk / top) * 100)}%`, background: mood.bar }}
            />
          </span>
        </span>
      </Link>
    </li>
  );
}

/** The stories the neighborhood can't stop talking about, biggest first. */
export function TalkedAbout() {
  const items: Rail["talkedAbout"] = useRail().data?.talkedAbout ?? [];
  const [first, ...rest] = items.slice(0, 4);
  if (!first) return null;
  const top = Math.max(...items.map((t) => t.posts + t.comments), 1);
  return (
    <section aria-label="Talked about" className="shrink-0 rounded-2xl border border-line bg-surface p-2.5">
      <h2 className="flex items-center gap-2 px-1 pb-2 text-sm font-extrabold tracking-tight">
        <span className="relative flex size-2" aria-hidden="true">
          <span className="absolute inline-flex size-full animate-ping rounded-full bg-[#f03e3e] opacity-70" />
          <span className="relative inline-flex size-2 rounded-full bg-[#f03e3e]" />
        </span>
        Everyone's talking about
      </h2>
      <Hero story={first} />
      {rest.length > 0 && (
        <ol className="mt-1">
          {rest.map((t, i) => (
            <Row key={t.postId} story={t} rank={i + 2} top={top} />
          ))}
        </ol>
      )}
    </section>
  );
}

/** Open-Meteo's CC BY 4.0 license requires this credit wherever its data shows. */
export function WeatherCredit() {
  return (
    <p className="pb-2 text-center text-xs text-muted">
      Weather data by{" "}
      <a href="https://open-meteo.com/" target="_blank" rel="noopener noreferrer" className="underline hover:text-ink">
        Open-Meteo.com
      </a>
    </p>
  );
}
