import { type QueryClient, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { keys, refreshPost } from "../api/queries.ts";
import { live } from "./store.ts";

type Data = Record<string, unknown>;

function describe(d: Data): string {
  const actors = (d.actors as string[] | undefined) ?? [];
  const count = (d.count as number | undefined) ?? actors.length;
  const who = actors.length === 0 ? "Someone" : count > 1 ? `${actors[0]} and ${count - 1} other${count > 2 ? "s" : ""}` : actors[0];
  switch (d.kind) {
    case "REACTION":
      return `${who} reacted to your ${d.refType === "COMMENT" ? "comment" : "post"}`;
    case "COMMENT":
      return `${who} commented on your post`;
    case "REPLY":
      return `${who} replied to your comment`;
    case "MENTION":
      return `${who} mentioned you`;
    case "DM":
      return `${who} sent you a message`;
    default:
      return `${who} — new activity`;
  }
}

/**
 * Applies one stream event to the query cache and the live store (SPEC §8): new posts go behind the
 * pill, comments and reactions patch the posts they land on, typing indicators come and go.
 */
export function applyEvent(qc: QueryClient, name: string, d: Data, refresh = refreshPost) {
  switch (name) {
    case "feed.new":
      live.addNewPosts((d.count as number) ?? 1);
      break;
    case "comment.aired": {
      const postId = d.postId as string;
      void qc.invalidateQueries({ queryKey: keys.comments(postId) });
      void refresh(qc, postId);
      break;
    }
    case "reaction.changed":
      if (d.targetType === "POST") void refresh(qc, d.targetId as string);
      else void qc.invalidateQueries({ queryKey: ["comments"] });
      break;
    case "typing.started":
      live.typingStarted(d.postId as string, { key: d.key as string, actorId: d.actorId as string, name: d.name as string });
      break;
    case "typing.stopped":
      live.typingStopped(d.postId as string, d.key as string);
      break;
    case "notification.created": {
      void qc.invalidateQueries({ queryKey: keys.notifications });
      const postId = d.refType === "POST" ? (d.refId as string) : null;
      live.toast({ id: `${Date.now()}-${Math.random()}`, text: describe(d), postId });
      break;
    }
    case "backfill.progress":
      live.setBackfilling(true);
      break;
    default:
      break;
  }
}

const EVENTS = [
  "feed.new",
  "post.aired",
  "comment.aired",
  "reaction.changed",
  "typing.started",
  "typing.stopped",
  "notification.created",
  "backfill.progress",
];

/** Subscribes to {@code /api/stream} while signed in; EventSource reconnects on its own. */
export function useRadianceStream(enabled: boolean) {
  const qc = useQueryClient();
  useEffect(() => {
    if (!enabled || typeof EventSource === "undefined") return;
    const source = new EventSource("/api/stream", { withCredentials: true });
    const pending = new Map<string, ReturnType<typeof setTimeout>>();
    // several reactions on one post in a burst: refetch it once
    const debounced = (client: QueryClient, id: string) => {
      clearTimeout(pending.get(id));
      pending.set(id, setTimeout(() => void refreshPost(client, id), 250));
      return Promise.resolve();
    };
    const handlers = EVENTS.map((name) => {
      const h = (e: MessageEvent) => {
        try {
          applyEvent(qc, name, JSON.parse(e.data) as Data, debounced);
        } catch {
          // a malformed event is not worth breaking the stream over
        }
      };
      source.addEventListener(name, h as EventListener);
      return [name, h] as const;
    });
    return () => {
      handlers.forEach(([name, h]) => source.removeEventListener(name, h as EventListener));
      pending.forEach((t) => clearTimeout(t));
      source.close();
    };
  }, [enabled, qc]);
}
