import { useQueryClient } from "@tanstack/react-query";
import { Sun } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { type FeedMode, useFeed } from "../api/queries.ts";
import type { Actor } from "../api/types.ts";
import { Composer } from "../feed/Composer.tsx";
import { NewPostsPill } from "../feed/NewPostsPill.tsx";
import { PostCard } from "../feed/PostCard.tsx";
import { RecapCard } from "../feed/RecapCard.tsx";
import { live } from "../live/store.ts";
import { Empty, PostSkeleton, Segmented } from "../ui/kit.tsx";

/**
 * The feed (SPEC §5.8, §8): composer, recap, and posts straight from the database, so it's there at
 * first paint. New posts that air while R reads wait behind the pill.
 */
export function FeedPage({ me }: { me: Actor }) {
  const [mode, setMode] = useState<FeedMode>("recent");
  const feed = useFeed(mode);
  const qc = useQueryClient();
  const sentinel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = sentinel.current;
    if (!el || typeof IntersectionObserver === "undefined") return;
    const io = new IntersectionObserver((entries) => {
      if (entries[0]?.isIntersecting && feed.hasNextPage && !feed.isFetchingNextPage) void feed.fetchNextPage();
    }, { rootMargin: "600px" });
    io.observe(el);
    return () => io.disconnect();
  }, [feed]);

  const posts = feed.data?.pages.flatMap((p) => p.items) ?? [];
  return (
    <>
      <NewPostsPill
        onShow={() => {
          void qc.resetQueries({ queryKey: ["feed"] });
        }}
      />
      <Composer me={me} />
      <RecapCard />
      <div className="flex items-center justify-between px-1">
        <h1 className="text-lg font-extrabold tracking-tight">Your feed</h1>
        <Segmented
          label="Feed order"
          value={mode}
          onChange={(m) => {
            setMode(m);
            live.clearNewPosts();
          }}
          options={[
            { value: "recent", label: "Recent" },
            { value: "top", label: "Top" },
          ]}
        />
      </div>
      {feed.isPending && (
        <div className="space-y-4">
          <PostSkeleton />
          <PostSkeleton />
          <PostSkeleton />
        </div>
      )}
      {feed.isError && <Empty icon={<Sun className="size-6" />} title="The feed didn't load">Check that the backend is running, then refresh.</Empty>}
      {!feed.isPending && posts.length === 0 && (
        <Empty icon={<Sun className="size-6" />} title="It's quiet out there">
          Nothing has aired yet. Say something — someone's bound to answer.
        </Empty>
      )}
      {posts.map((p) => (
        <PostCard key={p.id} post={p} me={me} />
      ))}
      <div ref={sentinel} />
      {feed.isFetchingNextPage && <PostSkeleton />}
      {!feed.hasNextPage && posts.length > 0 && (
        <p className="py-6 text-center text-sm text-faint">You're all caught up.</p>
      )}
    </>
  );
}
