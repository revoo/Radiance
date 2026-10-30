import { ArrowLeft, MessagesSquare, Search, SendHorizontal, SquarePen } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useConversation, useOpenThread, usePeople, useReadThread, useSendDm, useThreads } from "../api/queries.ts";
import type { Actor, DmMessage, DmThread } from "../api/types.ts";
import { live, useLive, useWorldNow } from "../live/store.ts";
import { emojiOnly, fullTime, relativeTime } from "../ui/format.ts";
import { Avatar, Empty, Modal, Skeleton } from "../ui/kit.tsx";

const EMPTY: never[] = [];
const GROUP_GAP = 5 * 60_000;
const DAY_GAP = 60 * 60_000;

function Dots() {
  return (
    <span className="flex items-center gap-1" aria-hidden="true">
      <span className="typing-dot size-2 rounded-full bg-muted" />
      <span className="typing-dot size-2 rounded-full bg-muted" />
      <span className="typing-dot size-2 rounded-full bg-muted" />
    </span>
  );
}

function ThreadRow({ t, active }: { t: DmThread; active: boolean }) {
  const now = useWorldNow();
  const typing = useLive((s) => s.typing[`dm:${t.id}`] ?? EMPTY).length > 0 || t.typing;
  const last = t.lastMessage;
  const unread = t.unread > 0;
  return (
    <Link
      to={`/messages/${t.id}`}
      aria-current={active ? "page" : undefined}
      className={`flex items-center gap-3 rounded-2xl px-3 py-2.5 transition ${active ? "bg-accent-soft" : "hover:bg-panel-2"}`}
    >
      <Avatar actor={t.with} size="lg" link={false} ring={typing} />
      <span className="min-w-0 flex-1">
        <span className={`block truncate text-[15px] ${unread ? "font-extrabold" : "font-semibold"}`}>{t.with.name}</span>
        <span className={`flex items-center gap-1 text-[13px] ${unread ? "font-semibold text-ink" : "text-muted"}`}>
          {typing ? (
            <span className="flex items-center gap-1.5 text-accent">
              typing <Dots />
            </span>
          ) : last ? (
            <>
              <span className="truncate">{last.you ? `You: ${last.body}` : last.body}</span>
              <span aria-hidden="true">·</span>
              <span className="shrink-0">{relativeTime(last.createdAt, now)}</span>
            </>
          ) : (
            <span>Say hi</span>
          )}
        </span>
      </span>
      {unread && (
        <span className="animate-pop min-w-5 rounded-full bg-accent px-1.5 text-center text-[11px] font-bold leading-5 text-white">
          {t.unread}
        </span>
      )}
    </Link>
  );
}

function NewMessage({ onClose }: { onClose: () => void }) {
  const friends = usePeople("friends");
  const open = useOpenThread();
  const navigate = useNavigate();
  const [q, setQ] = useState("");
  const shown = (friends.data ?? []).filter((a) => a.name.toLowerCase().includes(q.toLowerCase()) || a.handle.includes(q.toLowerCase()));
  return (
    <Modal title="New message" onClose={onClose}>
      <label className="flex items-center gap-2 rounded-xl bg-panel-2 px-3 py-2">
        <Search className="size-4 text-faint" aria-hidden="true" />
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Search friends"
          aria-label="Search friends"
          className="flex-1 bg-transparent outline-none placeholder:text-faint"
        />
      </label>
      <ul className="mt-3 max-h-80 overflow-y-auto">
        {shown.map((a) => (
          <li key={a.id}>
            <button
              onClick={() =>
                open.mutate(a.handle, {
                  onSuccess: (t) => {
                    onClose();
                    navigate(`/messages/${t.id}`);
                  },
                })
              }
              className="flex w-full items-center gap-3 rounded-xl px-2 py-2 text-left transition hover:bg-panel-2"
            >
              <Avatar actor={a} size="md" link={false} />
              <span className="min-w-0">
                <span className="block truncate font-semibold">{a.name}</span>
                <span className="block truncate text-xs text-muted">{a.occupation ?? `@${a.handle}`}</span>
              </span>
            </button>
          </li>
        ))}
      </ul>
    </Modal>
  );
}

function ThreadList({ activeId }: { activeId: string | undefined }) {
  const threads = useThreads();
  const [picking, setPicking] = useState(false);
  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex items-center justify-between px-4 pb-2 pt-4">
        <h1 className="text-2xl font-extrabold tracking-tight">Chats</h1>
        <button onClick={() => setPicking(true)} aria-label="New message" className="rounded-full bg-panel-2 p-2.5 transition hover:bg-line">
          <SquarePen className="size-5" />
        </button>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-3">
        {threads.isPending && (
          <div className="space-y-2 p-2">
            {[0, 1, 2, 3].map((i) => (
              <Skeleton key={i} className="h-14" />
            ))}
          </div>
        )}
        {threads.data?.length === 0 && (
          <Empty icon={<MessagesSquare className="size-6" />} title="No chats yet">
            Message a friend — they'll answer in seconds.
          </Empty>
        )}
        {threads.data?.map((t) => (
          <ThreadRow key={t.id} t={t} active={t.id === activeId} />
        ))}
      </div>
      {picking && <NewMessage onClose={() => setPicking(false)} />}
    </div>
  );
}

function dayLabel(at: number, now: number): string {
  const d = new Date(at);
  if (now - at < 20 * 3_600_000 && d.getDate() === new Date(now).getDate()) {
    return d.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" });
  }
  return d.toLocaleString("en-US", { weekday: "short", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
}

function Bubble({ m, first, last, them, fresh }: { m: DmMessage; first: boolean; last: boolean; them: Actor; fresh: boolean }) {
  const big = emojiOnly(m.body);
  const mine = m.you;
  const shape = mine
    ? `rounded-3xl ${first ? "" : "rounded-tr-md"} ${last ? "" : "rounded-br-md"}`
    : `rounded-3xl ${first ? "" : "rounded-tl-md"} ${last ? "" : "rounded-bl-md"}`;
  return (
    <div className={`flex items-end gap-2 ${mine ? "justify-end" : "justify-start"} ${fresh ? "animate-rise" : ""}`}>
      {!mine && (
        <span className="w-7 shrink-0">{last && <Avatar actor={them} size="xs" link={false} />}</span>
      )}
      <div
        title={fullTime(m.createdAt)}
        className={
          big
            ? "px-1 text-4xl leading-tight"
            : `max-w-[75%] whitespace-pre-wrap break-words px-4 py-2 text-[15px] leading-snug ${shape} ${
                mine ? "bg-brand text-white shadow-[0_6px_16px_-10px_rgb(240_56_107/0.8)]" : "bg-panel-2 text-ink"
              } ${m.id.startsWith("temp-") ? "opacity-70" : ""}`
        }
      >
        {m.body}
      </div>
    </div>
  );
}

function Chat({ threadId, me }: { threadId: string; me: Actor }) {
  const convo = useConversation(threadId);
  const send = useSendDm(threadId, me);
  const read = useReadThread();
  const now = useWorldNow();
  const typingLive = useLive((s) => s.typing[`dm:${threadId}`] ?? EMPTY);
  const [text, setText] = useState("");
  const end = useRef<HTMLDivElement>(null);
  const box = useRef<HTMLTextAreaElement>(null);
  const messages = convo.data?.messages ?? EMPTY;
  const typing = typingLive.length ? typingLive : (convo.data?.typing ?? EMPTY);
  const them = convo.data?.thread.with;
  const lastTheirs = [...messages].reverse().find((m) => !m.you)?.id;

  useEffect(() => {
    live.setOpenThread(threadId);
    return () => live.setOpenThread(null);
  }, [threadId]);

  // keep the newest message in view
  useEffect(() => {
    end.current?.scrollIntoView?.({ block: "end" });
  }, [messages.length, typing.length]);

  // reading the thread gives their messages a Seen
  const readFor = useRef<string | null>(null);
  useEffect(() => {
    if (!lastTheirs || readFor.current === lastTheirs || !convo.data?.thread.unread) return;
    readFor.current = lastTheirs;
    read.mutate(threadId);
  }, [lastTheirs, convo.data?.thread.unread, threadId, read]);

  const lastMine = useMemo(() => [...messages].reverse().find((m) => m.you && !m.id.startsWith("temp-")), [messages]);
  const submit = (body: string) => {
    const t = body.trim();
    if (!t) return;
    send.mutate(t);
    setText("");
    if (box.current) box.current.style.height = "auto";
  };

  if (convo.isPending) {
    return (
      <div className="space-y-3 p-6">
        <Skeleton className="h-10 w-48" />
        <Skeleton className="ml-auto h-10 w-56 rounded-3xl" />
        <Skeleton className="h-10 w-40 rounded-3xl" />
      </div>
    );
  }
  if (convo.isError || !them) {
    return (
      <Empty icon={<MessagesSquare className="size-6" />} title="This chat isn't here">
        Pick a conversation, or start a new one.
      </Empty>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <header className="flex items-center gap-3 border-b border-line px-4 py-3">
        <Link to="/messages" aria-label="Back to chats" className="rounded-full p-1.5 text-muted hover:bg-panel-2 md:hidden">
          <ArrowLeft className="size-5" />
        </Link>
        <Avatar actor={them} size="md" ring={typing.length > 0} />
        <div className="min-w-0 leading-tight">
          <Link to={`/people/${them.handle}`} className="block truncate font-bold hover:underline">
            {them.name}
          </Link>
          <span className="text-xs text-muted">{typing.length ? "typing…" : (them.occupation ?? `@${them.handle}`)}</span>
        </div>
      </header>

      <div className="min-h-0 flex-1 space-y-1 overflow-y-auto px-3 py-4" aria-live="polite">
        {messages.length === 0 && (
          <div className="flex flex-col items-center gap-2 py-10 text-center">
            <Avatar actor={them} size="xl" link={false} />
            <p className="mt-2 text-lg font-bold">{them.name}</p>
            <p className="text-sm text-muted">Say hi. They usually answer in seconds.</p>
          </div>
        )}
        {messages.map((m, i) => {
          const prev = messages[i - 1];
          const next = messages[i + 1];
          const first = !prev || prev.you !== m.you || m.createdAt - prev.createdAt > GROUP_GAP;
          const last = !next || next.you !== m.you || next.createdAt - m.createdAt > GROUP_GAP;
          const separator = !prev || m.createdAt - prev.createdAt > DAY_GAP;
          return (
            <div key={m.id} className={first && i > 0 ? "pt-2" : ""}>
              {separator && (
                <p className="py-3 text-center text-[11px] font-semibold uppercase tracking-wider text-faint">
                  {dayLabel(m.createdAt, now)}
                </p>
              )}
              <Bubble m={m} first={first} last={last} them={them} fresh={now - m.createdAt < 10_000} />
              {lastMine?.id === m.id && m.readAt && (
                <p className="animate-rise pr-1 pt-1 text-right text-[11px] font-semibold text-muted">
                  Seen {relativeTime(m.readAt, now) === "Just now" ? "just now" : relativeTime(m.readAt, now)}
                </p>
              )}
            </div>
          );
        })}
        {typing.length > 0 && (
          <div className="animate-rise flex items-end gap-2 pt-2" role="status" aria-label={`${them.name.split(" ")[0]} is typing`}>
            <span className="w-7 shrink-0">
              <Avatar actor={them} size="xs" link={false} />
            </span>
            <span className="rounded-3xl bg-panel-2 px-4 py-3">
              <Dots />
            </span>
          </div>
        )}
        <div ref={end} />
      </div>

      <div className="flex items-end gap-2 border-t border-line p-3">
        <textarea
          ref={box}
          rows={1}
          value={text}
          aria-label={`Message ${them.name}`}
          placeholder="Aa"
          onChange={(e) => {
            setText(e.target.value);
            e.target.style.height = "auto";
            e.target.style.height = `${Math.min(e.target.scrollHeight, 140)}px`;
          }}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              submit(text);
            }
          }}
          className="max-h-36 flex-1 resize-none rounded-3xl bg-panel-2 px-4 py-2.5 text-[15px] outline-none placeholder:text-faint focus:ring-2 focus:ring-accent/30"
        />
        {text.trim() ? (
          <button onClick={() => submit(text)} aria-label="Send" className="bg-brand rounded-full p-2.5 text-white transition hover:brightness-110 active:scale-90">
            <SendHorizontal className="size-5" />
          </button>
        ) : (
          <button onClick={() => submit("👋")} aria-label="Send a wave" className="rounded-full p-1.5 text-3xl leading-none transition hover:scale-110 active:scale-90">
            <span aria-hidden="true">👋</span>
          </button>
        )}
      </div>
    </div>
  );
}

/** Messenger (SPEC §5.11, §8): chats on the left, the open conversation on the right; one at a time on a phone. */
export function MessengerPage({ me }: { me: Actor }) {
  const { threadId } = useParams();
  return (
    <section className="overflow-hidden rounded-2xl border border-line bg-panel shadow-card">
      <div className="grid h-[calc(100dvh-10rem)] min-h-[420px] sm:h-[calc(100dvh-7rem)] md:grid-cols-[300px_minmax(0,1fr)]">
        <div className={`min-h-0 border-line md:border-r ${threadId ? "hidden md:block" : ""}`}>
          <ThreadList activeId={threadId} />
        </div>
        <div className={`min-h-0 ${threadId ? "" : "hidden md:block"}`}>
          {threadId ? (
            <Chat key={threadId} threadId={threadId} me={me} />
          ) : (
            <div className="flex h-full items-center justify-center">
              <Empty icon={<MessagesSquare className="size-6" />} title="Your messages">
                Pick a chat, or start one with the pencil.
              </Empty>
            </div>
          )}
        </div>
      </div>
    </section>
  );
}
