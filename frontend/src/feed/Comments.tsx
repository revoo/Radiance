import { SendHorizontal } from "lucide-react";
import { useRef, useState } from "react";
import { useCreateComment, useReact } from "../api/queries.ts";
import type { Actor, Comment } from "../api/types.ts";
import { useWorldNow } from "../live/store.ts";
import { fullTime, relativeTime } from "../ui/format.ts";
import { Avatar, Name, RichText } from "../ui/kit.tsx";
import { ReactionPicker } from "./ReactionPicker.tsx";
import { ReactionSummary } from "./ReactionSummary.tsx";

/** A comment landed in the last few seconds: it flashes in, so live replies are hard to miss. */
const FRESH_MS = 15_000;

export function CommentItem({ comment, me, onReply, depth = 0 }: {
  comment: Comment;
  me: Actor | null | undefined;
  onReply: (c: Comment) => void;
  depth?: number;
}) {
  const react = useReact();
  const now = useWorldNow();
  const fresh = !comment.author.you && now - comment.createdAt < FRESH_MS && !comment.id.startsWith("temp-");
  return (
    <div className={depth ? "ml-11" : ""}>
      <div className="flex gap-2">
        <Avatar actor={comment.author} size={depth ? "xs" : "sm"} ring={comment.author.you} />
        <div className="min-w-0 flex-1">
          <div className="relative inline-block max-w-full">
            <div className={`rounded-2xl bg-panel-2 px-3 py-2 ${fresh ? "animate-flash" : ""}`}>
              <Name actor={comment.author} className="text-[13px]" />
              <RichText text={comment.body} className="text-[15px] leading-snug" />
            </div>
            {comment.reactions.total > 0 && (
              <span className="absolute -bottom-2.5 right-1 rounded-full border border-line bg-panel px-1 py-0.5 shadow-card">
                <ReactionSummary reactions={comment.reactions} size="sm" />
              </span>
            )}
          </div>
          <div className="mt-1 flex items-center gap-3 px-3 text-xs text-muted">
            <time dateTime={new Date(comment.createdAt).toISOString()} title={fullTime(comment.createdAt)}>
              {relativeTime(comment.createdAt, now)}
            </time>
            {!comment.id.startsWith("temp-") && (
              <>
                <ReactionPicker
                  compact
                  mine={comment.reactions.mine}
                  onPick={(kind) => react.mutate({ targetType: "COMMENT", targetId: comment.id, postId: comment.postId, kind })}
                />
                {me && (
                  <button className="font-bold hover:underline" onClick={() => onReply(comment)}>
                    Reply
                  </button>
                )}
              </>
            )}
          </div>
        </div>
      </div>
      {comment.replies.length > 0 && (
        <div className="mt-2 space-y-2">
          {comment.replies.map((r) => (
            <CommentItem key={r.id} comment={r} me={me} onReply={onReply} depth={1} />
          ))}
        </div>
      )}
    </div>
  );
}

/** Write a comment, or a reply. Enter sends; Shift+Enter is a new line. */
export function CommentBox({ postId, me, replyTo, onSent, inputRef }: {
  postId: string;
  me: Actor | null | undefined;
  replyTo: Comment | null;
  onSent: () => void;
  inputRef?: React.RefObject<HTMLTextAreaElement | null>;
}) {
  const [text, setText] = useState("");
  const create = useCreateComment(postId, me);
  const own = useRef<HTMLTextAreaElement>(null);
  const ref = inputRef ?? own;
  if (!me) return null;
  const send = () => {
    const t = text.trim();
    if (!t) return;
    create.mutate({ text: t, parentId: replyTo?.id ?? null });
    setText("");
    onSent();
  };
  return (
    <div className="flex items-start gap-2">
      <Avatar actor={me} size="sm" ring />
      <div className="flex flex-1 items-end gap-1 rounded-2xl bg-panel-2 py-1 pl-3 pr-1 focus-within:ring-2 focus-within:ring-accent/40">
        <textarea
          ref={ref}
          rows={1}
          value={text}
          aria-label={replyTo ? `Reply to ${replyTo.author.name}` : "Write a comment"}
          placeholder={replyTo ? `Reply to ${replyTo.author.name.split(" ")[0]}…` : "Write a comment…"}
          onChange={(e) => {
            setText(e.target.value);
            e.target.style.height = "auto";
            e.target.style.height = `${Math.min(e.target.scrollHeight, 160)}px`;
          }}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              send();
            }
          }}
          className="max-h-40 flex-1 resize-none bg-transparent py-1.5 text-[15px] outline-none placeholder:text-faint"
        />
        <button
          onClick={send}
          disabled={!text.trim()}
          aria-label="Send comment"
          className="rounded-full p-2 text-accent transition hover:bg-accent-soft disabled:text-faint disabled:hover:bg-transparent"
        >
          <SendHorizontal className="size-4" />
        </button>
      </div>
    </div>
  );
}
