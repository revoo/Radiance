import { CloudSun, Flame } from "lucide-react";
import { Link } from "react-router";
import { useRail } from "../api/queries.ts";
import { Card } from "../ui/kit.tsx";

export function WeatherChip() {
  const weather = useRail().data?.weather;
  if (!weather) return null;
  return (
    <p
      title={weather.description}
      className="inline-flex items-center gap-2 rounded-full border border-line bg-panel px-3 py-1.5 text-sm font-semibold tabular-nums"
    >
      <CloudSun className="size-4 text-muted" aria-hidden />
      {weather.tempF}°F · {weather.sky}
    </p>
  );
}

export function TalkedAbout() {
  const items = useRail().data?.talkedAbout ?? [];
  if (items.length === 0) return null;
  return (
    <Card className="p-3">
      <h2 className="flex items-center gap-1.5 px-1 pb-2 text-sm font-bold text-muted">
        <Flame className="size-4 text-accent" aria-hidden />
        Talked about
      </h2>
      <ol className="space-y-1">
        {items.map((t) => (
          <li key={t.postId}>
            <Link to={`/posts/${t.postId}`} className="block rounded-xl px-1 py-1.5 transition hover:bg-panel">
              <span className="line-clamp-2 text-sm font-semibold leading-snug">{t.title}</span>
              <span className="mt-0.5 block text-xs text-muted">
                {t.source} · {t.posts} {t.posts === 1 ? "post" : "posts"}
                {t.comments > 0 && ` · ${t.comments} ${t.comments === 1 ? "comment" : "comments"}`}
              </span>
            </Link>
          </li>
        ))}
      </ol>
    </Card>
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
