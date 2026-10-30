import { AtSign, Bell, CheckCheck, CornerDownRight, Heart, MessageCircle, Send } from "lucide-react";
import { useEffect } from "react";
import { Link } from "react-router";
import { useMarkNotificationsRead, useNotifications } from "../api/queries.ts";
import type { Notification } from "../api/types.ts";
import { worldNow } from "../live/store.ts";
import { relativeTime } from "../ui/format.ts";
import { Avatar, Button, Card, Empty, Skeleton } from "../ui/kit.tsx";

const KIND: Record<string, { icon: typeof Heart; color: string; verb: (n: Notification) => string }> = {
  REACTION: { icon: Heart, color: "bg-[#e41e3f]", verb: (n) => `reacted to your ${n.refType === "COMMENT" ? "comment" : "post"}` },
  COMMENT: { icon: MessageCircle, color: "bg-[#2f9e44]", verb: () => "commented on your post" },
  REPLY: { icon: CornerDownRight, color: "bg-[#1971c2]", verb: () => "replied to your comment" },
  MENTION: { icon: AtSign, color: "bg-[#9c36b5]", verb: () => "mentioned you" },
  DM: { icon: Send, color: "bg-[#0c8599]", verb: () => "sent you a message" },
};

function who(n: Notification): React.ReactNode {
  const [first, second] = n.actors;
  if (!first) return "Someone";
  if (n.actors.length === 1) return <b>{first.name}</b>;
  if (n.actors.length === 2) return <><b>{first.name}</b> and <b>{second!.name}</b></>;
  return <><b>{first.name}</b> and <b>{n.actors.length - 1} others</b></>;
}

/** R's notifications (SPEC §5.14), reactions on one thing grouped into one line. */
export function NotificationsPage() {
  const notes = useNotifications();
  const read = useMarkNotificationsRead();
  const unread = notes.data?.unread ?? 0;

  // opening the page reads them, after a beat so the unread dots register first
  useEffect(() => {
    if (unread === 0) return;
    const t = setTimeout(() => read.mutate(), 2_500);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [unread]);

  return (
    <Card className="overflow-hidden">
      <div className="flex items-center justify-between border-b border-line px-4 py-3">
        <h1 className="text-xl font-extrabold tracking-tight">Notifications</h1>
        <Button variant="ghost" onClick={() => read.mutate()} disabled={unread === 0} className="px-3 py-1.5">
          <CheckCheck className="size-4" /> Mark all read
        </Button>
      </div>
      {notes.isPending && (
        <div className="space-y-3 p-4">
          {Array.from({ length: 5 }, (_, i) => (
            <Skeleton key={i} className="h-14" />
          ))}
        </div>
      )}
      {notes.data?.items.length === 0 && (
        <Empty icon={<Bell className="size-6" />} title="Nothing yet">Post something. The neighbors are watching.</Empty>
      )}
      <ul>
        {notes.data?.items.map((n, i) => {
          const k = KIND[n.kind] ?? KIND.COMMENT!;
          const Icon = k.icon;
          const lead = n.actors[0];
          const body = (
            <div className={`flex items-start gap-3 px-4 py-3 transition hover:bg-panel-2 ${n.read ? "" : "bg-accent-soft/50"}`}>
              <span className="relative shrink-0">
                {lead ? <Avatar actor={lead} size="lg" link={false} /> : <span className="block size-14 rounded-full bg-panel-2" />}
                <span className={`absolute -bottom-1 -right-1 flex size-6 items-center justify-center rounded-full text-white ring-2 ring-panel ${k.color}`}>
                  <Icon className="size-3.5" aria-hidden="true" />
                </span>
              </span>
              <div className="min-w-0 flex-1 pt-1">
                <p className="text-[15px] leading-snug">
                  {who(n)} {k.verb(n)}
                  {n.snippet && <span className="text-muted">: “{n.snippet}”</span>}
                </p>
                <p className={`mt-0.5 text-xs ${n.read ? "text-muted" : "font-semibold text-accent"}`}>
                  {relativeTime(n.createdAt, worldNow())}
                </p>
              </div>
              {!n.read && <span className="mt-4 size-2.5 shrink-0 rounded-full bg-accent" aria-label="Unread" />}
            </div>
          );
          return (
            <li key={n.id} className="animate-rise" style={{ animationDelay: `${Math.min(i, 10) * 25}ms` }}>
              {n.postId ? <Link to={`/posts/${n.postId}`}>{body}</Link> : body}
            </li>
          );
        })}
      </ul>
    </Card>
  );
}
