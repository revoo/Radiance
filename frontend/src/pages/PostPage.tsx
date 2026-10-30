import { ArrowLeft, Search } from "lucide-react";
import { Link, useParams } from "react-router";
import { usePost } from "../api/queries.ts";
import type { Actor } from "../api/types.ts";
import { PostCard } from "../feed/PostCard.tsx";
import { Card, Empty, PostSkeleton } from "../ui/kit.tsx";

/** One post with its whole thread. */
export function PostPage({ me }: { me: Actor }) {
  const { id = "" } = useParams();
  const post = usePost(id);
  return (
    <>
      <Link to="/" className="inline-flex items-center gap-1.5 px-1 text-sm font-semibold text-muted hover:text-ink">
        <ArrowLeft className="size-4" /> Back to feed
      </Link>
      {post.isPending && <PostSkeleton />}
      {post.isError && (
        <Card>
          <Empty icon={<Search className="size-6" />} title="This post isn't here">
            It may not have aired yet, or it's only visible to their friends.
          </Empty>
        </Card>
      )}
      {post.data && <PostCard key={post.data.id} post={post.data} me={me} expanded />}
    </>
  );
}
