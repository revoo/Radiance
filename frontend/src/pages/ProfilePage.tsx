import { Briefcase, Cake, Heart, House, MapPin, MessageCircle, Pencil, Search, UserRoundCheck } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useOpenThread, usePerson, usePersonPosts, useUpdateProfile } from "../api/queries.ts";
import type { Actor, Person } from "../api/types.ts";
import { PostCard } from "../feed/PostCard.tsx";
import { colorFor } from "../ui/format.ts";
import { Avatar, Button, Card, Empty, Modal, PostSkeleton, Skeleton } from "../ui/kit.tsx";

const KIND_LABEL: Record<string, string> = {
  CLOSE_FRIEND: "Close friend",
  FRIEND: "Friend",
  FAMILY: "Family",
  COWORKER: "Coworker",
  PARTNER: "Partner",
  EX: "Ex",
  RIVAL: "Rival",
  ACQUAINTANCE: "Acquaintance",
  STRANGER: "Haven't met",
};

const STATUS_LABEL: Record<string, string> = {
  SINGLE: "Single",
  DATING: "In a relationship",
  ENGAGED: "Engaged",
  MARRIED: "Married",
  DIVORCED: "Divorced",
  WIDOWED: "Widowed",
  COMPLICATED: "It's complicated",
};

function birthdayLabel(mmdd: string): string {
  const [m, d] = mmdd.split("-").map(Number);
  if (!m || !d) return mmdd;
  return new Date(2000, m - 1, d).toLocaleDateString("en-US", { month: "long", day: "numeric" });
}

function EditProfile({ person, onClose }: { person: Person; onClose: () => void }) {
  const [name, setName] = useState(person.actor.name);
  const [bio, setBio] = useState(person.bio ?? "");
  const [employer, setEmployer] = useState(person.employer ?? "");
  const [livesIn, setLivesIn] = useState(person.actor.livesIn ?? "");
  const update = useUpdateProfile();
  const field = "mt-1.5 w-full rounded-xl border border-line bg-panel-2 px-4 py-2.5 outline-none transition focus:border-accent focus:bg-panel";
  return (
    <Modal title="Edit profile" onClose={onClose}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          update.mutate({ name, bio, employer, livesIn }, { onSuccess: onClose });
        }}
      >
        <label className="block text-sm font-semibold" htmlFor="pf-name">Name</label>
        <input id="pf-name" value={name} onChange={(e) => setName(e.target.value)} className={field} />
        <label className="mt-3 block text-sm font-semibold" htmlFor="pf-bio">Bio</label>
        <textarea id="pf-bio" rows={3} value={bio} onChange={(e) => setBio(e.target.value)} className={`${field} resize-none`} />
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          <div>
            <label className="block text-sm font-semibold" htmlFor="pf-work">Works at</label>
            <input id="pf-work" value={employer} onChange={(e) => setEmployer(e.target.value)} className={field} />
          </div>
          <div>
            <label className="block text-sm font-semibold" htmlFor="pf-lives">Lives in</label>
            <input id="pf-lives" value={livesIn} onChange={(e) => setLivesIn(e.target.value)} className={field} />
          </div>
        </div>
        <div className="mt-5 flex justify-end gap-2">
          <Button type="button" variant="soft" onClick={onClose}>Cancel</Button>
          <Button type="submit" busy={update.isPending} disabled={!name.trim()}>Save</Button>
        </div>
      </form>
    </Modal>
  );
}

function Intro({ person }: { person: Person }) {
  const rows = [
    person.actor.occupation && { icon: Briefcase, text: <>{person.actor.occupation}{person.employer && <> at <b>{person.employer}</b></>}</> },
    person.actor.livesIn && { icon: House, text: <>Lives in <b>{person.actor.livesIn}</b></> },
    person.hometown && { icon: MapPin, text: <>From <b>{person.hometown}</b></> },
    person.relationshipStatus && { icon: Heart, text: STATUS_LABEL[person.relationshipStatus] ?? person.relationshipStatus },
    person.birthday && { icon: Cake, text: <>Birthday <b>{birthdayLabel(person.birthday)}</b></> },
  ].filter(Boolean) as { icon: typeof Briefcase; text: React.ReactNode }[];
  return (
    <Card className="p-4">
      <h2 className="text-lg font-bold">Intro</h2>
      {person.bio && <p className="mt-2 text-center text-[15px]">{person.bio}</p>}
      <ul className="mt-3 space-y-2.5">
        {rows.map((r, i) => (
          <li key={i} className="flex items-center gap-3 text-[15px]">
            <r.icon className="size-5 shrink-0 text-faint" aria-hidden="true" />
            <span>{r.text}</span>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function FriendsGrid({ person }: { person: Person }) {
  if (person.friends.length === 0) return null;
  return (
    <Card className="p-4">
      <div className="flex items-baseline justify-between">
        <h2 className="text-lg font-bold">Friends</h2>
        <span className="text-sm text-muted">
          {person.friends.length}
          {!person.actor.you && person.mutualFriends > 0 && ` · ${person.mutualFriends} mutual`}
        </span>
      </div>
      <div className="mt-3 grid grid-cols-3 gap-3">
        {person.friends.slice(0, 9).map((f) => (
          <Link key={f.id} to={`/people/${f.handle}`} className="group flex flex-col items-center gap-1.5 text-center">
            <Avatar actor={f} size="lg" link={false} />
            <span className="line-clamp-2 text-xs font-semibold group-hover:text-accent">{f.name}</span>
          </Link>
        ))}
      </div>
    </Card>
  );
}

/** A profile (SPEC §8): cover, avatar, intro, friends with mutuals, and posts. R can edit his own. */
export function ProfilePage({ me }: { me: Actor }) {
  const { handle = "" } = useParams();
  const person = usePerson(handle);
  const posts = usePersonPosts(handle);
  const [editing, setEditing] = useState(false);
  const openThread = useOpenThread();
  const navigate = useNavigate();

  if (person.isPending) {
    return (
      <Card className="overflow-hidden">
        <Skeleton className="h-44 rounded-none" />
        <div className="p-4">
          <Skeleton className="h-6 w-48" />
        </div>
      </Card>
    );
  }
  if (person.isError || !person.data) {
    return (
      <Card>
        <Empty icon={<Search className="size-6" />} title="No one by that name">They may have moved away. Or never existed.</Empty>
      </Card>
    );
  }
  const p = person.data;
  const a = colorFor(p.actor.id);
  const b = colorFor(p.actor.id + "~");
  const items = posts.data?.pages.flatMap((pg) => pg.items) ?? [];
  return (
    <>
      <Card className="overflow-hidden">
        <div className="relative h-40 overflow-hidden sm:h-48" style={{ backgroundImage: `linear-gradient(135deg, ${a}, ${b})` }}>
          <div aria-hidden="true" className="animate-glow absolute -left-10 top-6 size-40 rounded-full bg-white/25 blur-2xl" />
          <div aria-hidden="true" className="animate-glow absolute right-6 -top-10 size-52 rounded-full bg-black/10 blur-3xl [animation-delay:1s]" />
        </div>
        <div className="flex flex-col items-center px-4 pb-5 sm:flex-row sm:items-end sm:gap-4">
          <div className="relative z-10 -mt-16 rounded-full bg-panel p-1.5 shadow-lift">
            <Avatar actor={p.actor} size="xl" ring={p.actor.you} link={false} />
          </div>
          <div className="mt-3 min-w-0 flex-1 text-center sm:text-left">
            <h1 className="text-2xl font-extrabold tracking-tight sm:text-3xl">{p.actor.name}</h1>
            <p className="text-sm text-muted">@{p.actor.handle}</p>
          </div>
          <div className="mt-3 flex gap-2">
            {!p.actor.you && (
              <Button
                busy={openThread.isPending}
                onClick={() => openThread.mutate(p.actor.handle, { onSuccess: (t) => navigate(`/messages/${t.id}`) })}
              >
                <MessageCircle className="size-4" /> Message
              </Button>
            )}
            {p.actor.you ? (
              <Button variant="soft" onClick={() => setEditing(true)}>
                <Pencil className="size-4" /> Edit profile
              </Button>
            ) : p.friend ? (
              <span className="inline-flex items-center gap-1.5 rounded-xl bg-accent-soft px-3.5 py-2 text-sm font-bold text-accent">
                <UserRoundCheck className="size-4" aria-hidden="true" />
                {KIND_LABEL[p.relationship ?? "FRIEND"] ?? "Friends"}
              </span>
            ) : (
              <span className="inline-flex items-center rounded-xl bg-panel-2 px-3.5 py-2 text-sm font-semibold text-muted">
                {KIND_LABEL[p.relationship ?? "STRANGER"] ?? "Not friends"}
              </span>
            )}
          </div>
        </div>
      </Card>

      <div className="grid gap-4 md:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        <div className="space-y-4">
          <Intro person={p} />
          <FriendsGrid person={p} />
        </div>
        <div className="space-y-4">
          {posts.isPending && <PostSkeleton />}
          {!posts.isPending && items.length === 0 && (
            <Card>
              <Empty icon={<Pencil className="size-6" />} title="No posts to show">
                {p.actor.you ? "Your posts will show up here." : p.friend ? "Nothing yet." : "Only public posts show up until you're friends."}
              </Empty>
            </Card>
          )}
          {items.map((post) => (
            <PostCard key={post.id} post={post} me={me} />
          ))}
          {posts.hasNextPage && (
            <Button variant="soft" className="w-full" busy={posts.isFetchingNextPage} onClick={() => void posts.fetchNextPage()}>
              Show more
            </Button>
          )}
        </div>
      </div>
      {editing && <EditProfile person={p} onClose={() => setEditing(false)} />}
    </>
  );
}
