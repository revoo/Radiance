import { Briefcase, MapPin, Users } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { usePeople } from "../api/queries.ts";
import { Avatar, Card, Empty, Segmented, Skeleton } from "../ui/kit.tsx";

type Filter = "friends" | "suggested" | "all";

/** Everyone in Chicagoland R knows, might know, or has heard of. */
export function FriendsPage() {
  const [filter, setFilter] = useState<Filter>("friends");
  const people = usePeople(filter);
  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-3 px-1">
        <h1 className="text-2xl font-extrabold tracking-tight">People</h1>
        <Segmented
          label="Who to show"
          value={filter}
          onChange={setFilter}
          options={[
            { value: "friends", label: "Friends" },
            { value: "suggested", label: "Suggested" },
            { value: "all", label: "Everyone" },
          ]}
        />
      </div>
      {people.isPending && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          {Array.from({ length: 6 }, (_, i) => (
            <Skeleton key={i} className="h-44 rounded-2xl" />
          ))}
        </div>
      )}
      {people.data?.length === 0 && (
        <Card>
          <Empty icon={<Users className="size-6" />} title={filter === "suggested" ? "No suggestions yet" : "Nobody here yet"}>
            {filter === "suggested" ? "People from your circles show up here." : "Friends you make will show up here."}
          </Empty>
        </Card>
      )}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        {people.data?.map((a, i) => (
          <Link
            key={a.id}
            to={`/people/${a.handle}`}
            style={{ animationDelay: `${Math.min(i, 12) * 30}ms` }}
            className="animate-rise group flex flex-col items-center rounded-2xl border border-line bg-panel p-4 text-center shadow-card transition hover:-translate-y-0.5 hover:shadow-lift"
          >
            <Avatar actor={a} size="lg" link={false} />
            <span className="mt-3 line-clamp-1 font-bold group-hover:text-accent">{a.name}</span>
            {a.occupation && (
              <span className="mt-1 flex items-center gap-1 text-xs text-muted">
                <Briefcase className="size-3 shrink-0" aria-hidden="true" />
                <span className="line-clamp-1">{a.occupation}</span>
              </span>
            )}
            {a.livesIn && (
              <span className="mt-0.5 flex items-center gap-1 text-xs text-muted">
                <MapPin className="size-3 shrink-0" aria-hidden="true" />
                <span className="line-clamp-1">{a.livesIn}</span>
              </span>
            )}
          </Link>
        ))}
      </div>
    </>
  );
}
