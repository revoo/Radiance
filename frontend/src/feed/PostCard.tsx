import { Globe, MessageCircle, UsersRound } from "lucide-react";
import { useRef, useState } from "react";
import { Link } from "react-router";
import { useComments, useReact } from "../api/queries.ts";
import type { Actor, Comment, Post } from "../api/types.ts";
import { useLive, useWorldNow } from "../live/store.ts";
import { fullTime, relativeTime } from "../ui/format.ts";
import { Avatar, Card, Name, RichText } from "../ui/kit.tsx";
import { CommentBox, CommentItem } from "./Comments.tsx";
import { ReactionPicker } from "./ReactionPicker.tsx";
import { ReactionSummary } from "./ReactionSummary.tsx";
import { TypingRow } from "./TypingRow.tsx";

const EMPTY: never[] = [];

/**
 * One post (SPEC §8): who and when, the text with mentions, the reaction summary, React and Comment,
 * two preview comments with "View N more", one level of replies, and a typing row while a resident is
 * writing. {@code expanded} shows the whole thread (the post page).
 */
export function PostCard({ post, me, expanded = false }: { post: Post; me: Actor | null | undefined; expanded?: boolean }) {
  const [open, setOpen] = useState(expanded);
  const [replyTo, setReplyTo] = useState<Comment | null>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  const react = useReact();
  const now = useWorldNow();
  const comments = useComments(post.id, open);
  const liveTyping = useLive((s) => s.typing[post.id] ?? EMPTY);
  const typing = liveTyping.length ? liveTyping : post.typing;
  const temp = post.id.startsWith("temp-");
  const short = post.body.length <= 85 && !post.body.includes("\n");
  const shown = open && comments.data ? comments.data : post.preview;
  const hidden = Math.max(0, post.commentCount - shown.reduce((n, c) => n + 1 + c.replies.length, 0));

  const focusBox = () => {
    setOpen(true);
    setTimeout(() => input.current?.focus(), 0);
  };

  return (
    <Card className={`animate-rise overflow-visible ${temp ? "opacity-70" : ""}`}>
      <article aria-label={`Post by ${post.author.name}`}>
        <header className="flex items-center gap-3 px-4 pt-4">
          <Avatar actor={post.author} ring={post.author.you} />
          <div className="min-w-0 leading-tight">
            <Name actor={post.author} />
            <div className="mt-0.5 flex items-center gap-1 text-xs text-muted">
              <Link to={temp ? "#" : `/posts/${post.id}`} className="hover:underline" title={fullTime(post.createdAt)}>
                {temp ? "Posting…" : relativeTime(post.createdAt, now)}
              </Link>
              <span aria-hidden="true">·</span>
              {post.audience === "PUBLIC" ? (
                <Globe className="size-3" aria-label="Public" />
              ) : (
                <UsersRound className="size-3" aria-label="Friends" />
              )}
            </div>
          </div>
        </header>

        <div className="px-4 pb-3 pt-3">
          <RichText text={post.body} className={short ? "text-[22px] font-medium leading-snug tracking-tight" : "text-[15px] leading-relaxed"} />
        </div>

        {(post.reactions.total > 0 || post.commentCount > 0) && (
          <div className="flex items-center justify-between px-4 pb-2">
            <ReactionSummary reactions={post.reactions} />
            {post.commentCount > 0 && (
              <button onClick={() => setOpen(true)} className="text-sm text-muted hover:underline">
                {post.commentCount} comment{post.commentCount === 1 ? "" : "s"}
              </button>
            )}
          </div>
        )}

        {!temp && (
          <div className="mx-3 grid grid-cols-2 gap-1 border-t border-line py-1">
            <ReactionPicker
              mine={post.reactions.mine}
              onPick={(kind) => react.mutate({ targetType: "POST", targetId: post.id, postId: post.id, kind })}
            />
            <button
              onClick={focusBox}
              className="flex items-center justify-center gap-2 rounded-xl py-2 text-sm font-semibold text-muted transition hover:bg-panel-2 active:scale-[0.97]"
            >
              <MessageCircle className="size-[18px]" aria-hidden="true" />
              Comment
            </button>
          </div>
        )}

        {(shown.length > 0 || typing.length > 0 || open) && !temp && (
          <div className="space-y-3 border-t border-line px-4 pb-4 pt-3">
            {hidden > 0 && !open && (
              <button onClick={() => setOpen(true)} className="text-sm font-semibold text-muted hover:underline">
                View {hidden} more comment{hidden === 1 ? "" : "s"}
              </button>
            )}
            {open && comments.isPending && <p className="text-sm text-muted">Loading comments…</p>}
            {shown.map((c) => (
              <CommentItem
                key={c.id}
                comment={c}
                me={me}
                onReply={(target) => {
                  setReplyTo(target);
                  focusBox();
                }}
              />
            ))}
            <TypingRow typing={typing} />
            {open && (
              <>
                {replyTo && (
                  <p className="flex items-center gap-2 pl-10 text-xs text-muted">
                    Replying to <span className="font-semibold text-ink">{replyTo.author.name}</span>
                    <button className="font-semibold hover:underline" onClick={() => setReplyTo(null)}>
                      Cancel
                    </button>
                  </p>
                )}
                <CommentBox postId={post.id} me={me} replyTo={replyTo} onSent={() => setReplyTo(null)} inputRef={input} />
              </>
            )}
          </div>
        )}
      </article>
    </Card>
  );
}
