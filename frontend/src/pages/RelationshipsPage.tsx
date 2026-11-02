import { TrendingDown, TrendingUp } from "lucide-react";
import { Link } from "react-router";
import { useRelationships } from "../api/queries.ts";
import type { Relationship } from "../api/types.ts";
import { Avatar, Card, Empty, Skeleton } from "../ui/kit.tsx";
import { LikeMeter, meterLabel } from "../ui/LikeMeter.tsx";

function Trend({ trend }: { trend: number | null }) {
  if (trend === null || trend === 0) return <span className="w-12 text-right text-xs text-muted">—</span>;
  const up = trend > 0;
  const Icon = up ? TrendingUp : TrendingDown;
  return (
    <span
      title="Change over the last 7 days"
      className={`inline-flex w-12 items-center justify-end gap-1 text-xs font-bold tabular-nums ${up ? "text-good" : "text-bad"}`}
    >
      <Icon className="size-3.5" aria-hidden="true" />
      {up ? "+" : "−"}
      {Math.abs(trend)}
    </span>
  );
}

function Row({ r }: { r: Relationship }) {
  return (
    <li>
      <Link
        to={`/people/${r.actor.handle}`}
        className="flex items-center gap-3 rounded-2xl px-3 py-2.5 transition hover:bg-panel"
      >
        <Avatar actor={r.actor} link={false} />
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[15px] font-semibold">{r.actor.name}</span>
          <span className="block truncate text-xs text-muted">{meterLabel(r.meter)}</span>
        </span>
        <span className="hidden sm:block">
          <LikeMeter value={r.meter} name={r.actor.name} compact />
        </span>
        <span className="w-10 text-right text-sm font-extrabold tabular-nums">
          {r.meter > 0 ? "+" : ""}
          {r.meter}
        </span>
        <Trend trend={r.trend} />
      </Link>
    </li>
  );
}

/** Everyone, by how they feel about R, with a 7-day trend (SPEC §5.9). */
export function RelationshipsPage() {
  const list = useRelationships();
  return (
    <>
      <div className="px-1">
        <h1 className="text-2xl font-extrabold tracking-tight">Relationships</h1>
        <p className="mt-1 text-sm text-muted">How everyone in Chicagoland feels about you.</p>
      </div>
      {list.isPending && <Skeleton className="h-96" />}
      {list.data?.length === 0 && <Empty title="Nobody yet" />}
      {list.data && list.data.length > 0 && (
        <Card className="p-2">
          <ul>
            {list.data.map((r) => (
              <Row key={r.actor.id} r={r} />
            ))}
          </ul>
        </Card>
      )}
    </>
  );
}
