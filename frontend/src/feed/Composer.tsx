import { Sparkles } from "lucide-react";
import { useMemo, useRef, useState } from "react";
import { useCreatePost, usePeople } from "../api/queries.ts";
import type { Actor } from "../api/types.ts";
import { Avatar, Button, Card } from "../ui/kit.tsx";

const MAX = 5_000;

/** Little nudges for a blank composer: tap one to start a post. */
const PROMPTS = [
  { label: "Share a win", text: "Small win today: " },
  { label: "Ask for a rec", text: "Okay Chicagoland, I need a recommendation: " },
  { label: "Hot take", text: "Hot take: " },
  { label: "What's up this weekend?", text: "Plans this weekend? I'm thinking " },
];

/** The word being typed at the caret, if it's an @mention. */
function mentionAt(text: string, caret: number): { start: number; query: string } | null {
  const before = text.slice(0, caret);
  const m = /(^|\s)@([a-z0-9_.]*)$/i.exec(before);
  if (!m) return null;
  return { start: caret - m[2]!.length - 1, query: m[2]!.toLowerCase() };
}

/**
 * "What's on your mind?" (SPEC §8): text posts with @mention autocomplete from R's friends. Ctrl/⌘+Enter
 * posts. The post shows in the feed at once; residents' responses follow at the Pacer's pace.
 */
export function Composer({ me }: { me: Actor }) {
  const [text, setText] = useState("");
  const [focused, setFocused] = useState(false);
  const [mention, setMention] = useState<{ start: number; query: string } | null>(null);
  const [active, setActive] = useState(0);
  const ref = useRef<HTMLTextAreaElement>(null);
  const create = useCreatePost(me);
  const friends = usePeople("friends");

  const matches = useMemo(() => {
    if (!mention || !friends.data) return [];
    const q = mention.query;
    return friends.data
      .filter((a) => a.handle.includes(q) || a.name.toLowerCase().includes(q))
      .slice(0, 5);
  }, [mention, friends.data]);

  const first = me.name.split(" ")[0];
  const expanded = focused || text.length > 0;

  const resize = (el: HTMLTextAreaElement) => {
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 320)}px`;
  };

  const insert = (a: Actor) => {
    if (!mention || !ref.current) return;
    const caret = ref.current.selectionStart;
    const next = `${text.slice(0, mention.start)}@${a.handle} ${text.slice(caret)}`;
    setText(next);
    setMention(null);
    requestAnimationFrame(() => {
      const pos = mention.start + a.handle.length + 2;
      ref.current?.setSelectionRange(pos, pos);
      ref.current?.focus();
    });
  };

  const submit = () => {
    const t = text.trim();
    if (!t || t.length > MAX) return;
    create.mutate(t);
    setText("");
    setMention(null);
    if (ref.current) ref.current.style.height = "auto";
  };

  return (
    <Card className="relative p-4">
      <div className="flex items-start gap-3">
        <Avatar actor={me} ring />
        <div className="relative min-w-0 flex-1">
          <textarea
            ref={ref}
            rows={expanded ? 3 : 1}
            value={text}
            aria-label="Write a post"
            placeholder={`What's on your mind, ${first}?`}
            onFocus={() => setFocused(true)}
            onBlur={() => setTimeout(() => setFocused(false), 150)}
            onChange={(e) => {
              setText(e.target.value);
              setMention(mentionAt(e.target.value, e.target.selectionStart));
              setActive(0);
              resize(e.target);
            }}
            onKeyDown={(e) => {
              if (matches.length > 0) {
                if (e.key === "ArrowDown") {
                  e.preventDefault();
                  setActive((i) => (i + 1) % matches.length);
                  return;
                }
                if (e.key === "ArrowUp") {
                  e.preventDefault();
                  setActive((i) => (i - 1 + matches.length) % matches.length);
                  return;
                }
                if (e.key === "Enter" || e.key === "Tab") {
                  e.preventDefault();
                  insert(matches[active]!);
                  return;
                }
                if (e.key === "Escape") {
                  setMention(null);
                  return;
                }
              }
              if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
                e.preventDefault();
                submit();
              }
            }}
            className={`w-full resize-none rounded-2xl bg-panel-2 px-4 py-2.5 text-[16px] outline-none transition-all placeholder:text-faint focus:bg-panel focus:ring-2 focus:ring-accent/30 ${
              expanded ? "min-h-24" : ""
            }`}
          />
          {matches.length > 0 && (
            <ul
              role="listbox"
              aria-label="Mention a friend"
              className="animate-rise absolute left-2 top-full z-30 mt-1 w-64 overflow-hidden rounded-2xl border border-line bg-panel py-1 shadow-lift"
            >
              {matches.map((a, i) => (
                <li key={a.id} role="option" aria-selected={i === active}>
                  <button
                    onMouseDown={(e) => {
                      e.preventDefault();
                      insert(a);
                    }}
                    className={`flex w-full items-center gap-2 px-3 py-2 text-left text-sm ${i === active ? "bg-panel-2" : ""}`}
                  >
                    <Avatar actor={a} size="sm" link={false} />
                    <span className="min-w-0">
                      <span className="block truncate font-semibold">{a.name}</span>
                      <span className="block truncate text-xs text-muted">@{a.handle}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      {!expanded && (
        <div className="scrollbar-none mt-3 flex gap-2 overflow-x-auto pl-[52px]">
          {PROMPTS.map((p) => (
            <button
              key={p.label}
              onClick={() => {
                setText(p.text);
                setFocused(true);
                requestAnimationFrame(() => {
                  ref.current?.focus();
                  ref.current?.setSelectionRange(p.text.length, p.text.length);
                });
              }}
              className="flex shrink-0 items-center gap-1.5 rounded-full border border-line px-3 py-1 text-xs font-semibold text-muted transition hover:border-accent/40 hover:bg-accent-soft hover:text-accent"
            >
              <Sparkles className="size-3" aria-hidden="true" />
              {p.label}
            </button>
          ))}
        </div>
      )}

      {expanded && (
        <div className="animate-rise mt-3 flex items-center justify-between pl-[52px]">
          <span className={`text-xs ${text.length > MAX ? "text-bad" : "text-faint"}`}>
            {text.length > MAX - 300 ? `${MAX - text.length} left` : "Ctrl + Enter to post · @ to mention a friend"}
          </span>
          <Button onClick={submit} disabled={!text.trim() || text.length > MAX} className="px-6">
            Post
          </Button>
        </div>
      )}
    </Card>
  );
}
