import {
  type InfiniteData,
  type QueryClient,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { live } from "../live/store.ts";
import { api, ApiError } from "./client.ts";
import type {
  Comment,
  DmConversation,
  DmMessage,
  DmThread,
  Me,
  Notifications,
  Onboard,
  PacerMonitor,
  Page,
  Person,
  Post,
  Profile,
  ReactionKind,
  Reactions,
  Recap,
  World,
  Actor,
} from "./types.ts";

export type FeedMode = "recent" | "top";

export const keys = {
  me: ["me"] as const,
  world: ["world"] as const,
  feed: (mode: FeedMode) => ["feed", mode] as const,
  post: (id: string) => ["post", id] as const,
  comments: (id: string) => ["comments", id] as const,
  recap: ["recap"] as const,
  notifications: ["notifications"] as const,
  people: (filter: string) => ["people", filter] as const,
  person: (handle: string) => ["person", handle] as const,
  personPosts: (handle: string) => ["person-posts", handle] as const,
  profile: ["profile"] as const,
  pacer: ["pacer"] as const,
  threads: ["dm-threads"] as const,
  conversation: (id: string) => ["dm", id] as const,
};

export function useMe() {
  return useQuery({ queryKey: keys.me, queryFn: () => api<Me>("/api/me"), retry: false, staleTime: 60_000 });
}

export function useWorld() {
  return useQuery({ queryKey: keys.world, queryFn: () => api<World>("/api/world"), staleTime: 30_000 });
}

export function useFeed(mode: FeedMode) {
  return useInfiniteQuery({
    queryKey: keys.feed(mode),
    queryFn: ({ pageParam }) =>
      api<Page<Post>>(`/api/feed?mode=${mode}&limit=20${pageParam ? `&cursor=${encodeURIComponent(pageParam)}` : ""}`),
    initialPageParam: "",
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    // new posts wait behind the pill; they never shift the feed under R (SPEC §5.8)
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });
}

export function usePost(id: string) {
  return useQuery({ queryKey: keys.post(id), queryFn: () => api<Post>(`/api/posts/${id}`) });
}

export function useComments(postId: string, enabled = true) {
  return useQuery({
    queryKey: keys.comments(postId),
    queryFn: () => api<Comment[]>(`/api/posts/${postId}/comments`),
    enabled,
  });
}

export function useRecap() {
  return useQuery({ queryKey: keys.recap, queryFn: () => api<Recap>("/api/world/recap"), staleTime: Infinity });
}

export function useNotifications() {
  return useQuery({ queryKey: keys.notifications, queryFn: () => api<Notifications>("/api/notifications") });
}

export function usePeople(filter: "friends" | "all" | "suggested") {
  return useQuery({ queryKey: keys.people(filter), queryFn: () => api<Actor[]>(`/api/people?filter=${filter}`) });
}

export function usePerson(handle: string) {
  return useQuery({ queryKey: keys.person(handle), queryFn: () => api<Person>(`/api/people/${handle}`) });
}

export function usePersonPosts(handle: string) {
  return useInfiniteQuery({
    queryKey: keys.personPosts(handle),
    queryFn: ({ pageParam }) =>
      api<Page<Post>>(`/api/people/${handle}/posts?limit=20${pageParam ? `&cursor=${encodeURIComponent(pageParam)}` : ""}`),
    initialPageParam: "",
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
}

export function useProfile() {
  return useQuery({ queryKey: keys.profile, queryFn: () => api<Profile>("/api/profile"), retry: false });
}

export function usePacer() {
  return useQuery({ queryKey: keys.pacer, queryFn: () => api<PacerMonitor>("/api/director/pacer"), refetchInterval: 2_000 });
}

// ------------------------------------------------------------------------------------- cache patches

/** Replaces a post wherever it's cached (feeds, profile lists, its own query). */
export function patchPost(qc: QueryClient, id: string, update: (p: Post) => Post) {
  const patchPages = (data: InfiniteData<Page<Post>> | undefined) =>
    data && {
      ...data,
      pages: data.pages.map((page) => ({ ...page, items: page.items.map((p) => (p.id === id ? update(p) : p)) })),
    };
  qc.setQueriesData<InfiniteData<Page<Post>>>({ queryKey: ["feed"] }, patchPages);
  qc.setQueriesData<InfiniteData<Page<Post>>>({ queryKey: ["person-posts"] }, patchPages);
  qc.setQueryData<Post>(keys.post(id), (p) => (p ? update(p) : p));
}

/** Fetches a post again and patches it in place (a comment or reaction landed on it). */
export async function refreshPost(qc: QueryClient, id: string) {
  try {
    const fresh = await api<Post>(`/api/posts/${id}`);
    patchPost(qc, id, (p) => ({ ...fresh, typing: p.typing }));
  } catch {
    // not visible to R, or gone: nothing to patch
  }
}

export function withReaction(r: Reactions, kind: ReactionKind | null): Reactions {
  const byKind = { ...r.byKind };
  let total = r.total;
  if (r.mine) {
    byKind[r.mine] = (byKind[r.mine] ?? 1) - 1;
    if (!byKind[r.mine]) delete byKind[r.mine];
    total -= 1;
  }
  if (kind) {
    byKind[kind] = (byKind[kind] ?? 0) + 1;
    total += 1;
  }
  const top = (Object.entries(byKind) as [ReactionKind, number][])
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .slice(0, 3)
    .map(([k]) => k);
  return { ...r, byKind, total, top, mine: kind };
}

// ----------------------------------------------------------------------------------------- mutations

/** R's post: echoed at once in the feed, then replaced by the server's copy. */
export function useCreatePost(me: Actor | null | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (text: string) => api<Post>("/api/posts", { json: { text } }),
    onMutate: async (text) => {
      await qc.cancelQueries({ queryKey: ["feed", "recent"] });
      const temp: Post = {
        id: `temp-${Date.now()}`,
        author: me ?? { id: "you", handle: "you", name: "You", you: true, occupation: null, livesIn: null },
        createdAt: Date.now(),
        kind: "STATUS",
        audience: "FRIENDS",
        body: text,
        reactions: { total: 0, top: [], byKind: {}, mine: null, recent: [] },
        commentCount: 0,
        preview: [],
        typing: [],
      };
      qc.setQueryData<InfiniteData<Page<Post>>>(keys.feed("recent"), (data) => {
        const first = data?.pages[0];
        if (!data || !first) return data;
        return { ...data, pages: [{ ...first, items: [temp, ...first.items] }, ...data.pages.slice(1)] };
      });
      return { tempId: temp.id };
    },
    onSuccess: (post, _text, ctx) => {
      qc.setQueryData<InfiniteData<Page<Post>>>(keys.feed("recent"), (data) =>
        data
          ? {
              ...data,
              pages: data.pages.map((page) => ({
                ...page,
                items: page.items.map((p) => (p.id === ctx?.tempId ? post : p)),
              })),
            }
          : data,
      );
    },
    onError: (e, _text, ctx) => {
      live.toast({
        id: `post-failed-${Date.now()}`,
        text: e instanceof ApiError && e.status === 503 ? "The world is waking up. Try posting again in a few seconds." : "Your post didn't go through. Try again.",
        postId: null,
      });
      qc.setQueryData<InfiniteData<Page<Post>>>(keys.feed("recent"), (data) =>
        data
          ? { ...data, pages: data.pages.map((page) => ({ ...page, items: page.items.filter((p) => p.id !== ctx?.tempId) })) }
          : data,
      );
    },
  });
}

/** R's comment or reply: shown at once in the thread. */
export function useCreateComment(postId: string, me: Actor | null | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { text: string; parentId?: string | null }) =>
      api<Comment>(`/api/posts/${postId}/comments`, { json: { text: v.text, parentId: v.parentId ?? null } }),
    onMutate: async (v) => {
      await qc.cancelQueries({ queryKey: keys.comments(postId) });
      const temp: Comment = {
        id: `temp-${Date.now()}`,
        postId,
        parentId: v.parentId ?? null,
        author: me ?? { id: "you", handle: "you", name: "You", you: true, occupation: null, livesIn: null },
        createdAt: Date.now(),
        body: v.text,
        reactions: { total: 0, top: [], byKind: {}, mine: null, recent: [] },
        replies: [],
      };
      qc.setQueryData<Comment[]>(keys.comments(postId), (list) => {
        if (!list) return list;
        if (!v.parentId) return [...list, temp];
        return list.map((c) =>
          c.id === v.parentId || c.replies.some((r) => r.id === v.parentId) ? { ...c, replies: [...c.replies, temp] } : c,
        );
      });
      if (!v.parentId) {
        patchPost(qc, postId, (p) => ({ ...p, commentCount: p.commentCount + 1, preview: [...p.preview, temp].slice(-2) }));
      }
      return { tempId: temp.id };
    },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: keys.comments(postId) });
      void refreshPost(qc, postId);
    },
  });
}

/** R reacts (or takes it back): the count moves at once. */
export function useReact() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { targetType: "POST" | "COMMENT"; targetId: string; postId: string; kind: ReactionKind | null }) =>
      v.kind
        ? api<void>("/api/reactions", { method: "PUT", json: { targetType: v.targetType, targetId: v.targetId, kind: v.kind } })
        : api<void>(`/api/reactions/${v.targetType}/${v.targetId}`, { method: "DELETE" }),
    onMutate: (v) => {
      if (v.targetType === "POST") {
        patchPost(qc, v.targetId, (p) => ({ ...p, reactions: withReaction(p.reactions, v.kind) }));
      } else {
        const patch = (c: Comment): Comment => ({
          ...(c.id === v.targetId ? { ...c, reactions: withReaction(c.reactions, v.kind) } : c),
          replies: c.replies.map(patch),
        });
        qc.setQueryData<Comment[]>(keys.comments(v.postId), (list) => list?.map(patch));
        patchPost(qc, v.postId, (p) => ({ ...p, preview: p.preview.map(patch) }));
      }
    },
    onError: (_e, v) => {
      void refreshPost(qc, v.postId);
      void qc.invalidateQueries({ queryKey: keys.comments(v.postId) });
    },
  });
}

export function useLogin() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { username: string; password: string }) => api<{ username: string }>("/api/auth/login", { form: v }),
    onSuccess: () => qc.invalidateQueries(),
  });
}

export function useLogout() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api<void>("/api/auth/logout", { method: "POST" }),
    // even if the session was already gone: forget everything, then ask who R is again (a 401 → login)
    onSettled: async () => {
      live.reset();
      qc.removeQueries({ predicate: (q) => q.queryKey[0] !== keys.me[0] });
      await qc.resetQueries({ queryKey: keys.me });
    },
  });
}

export function useOnboard() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: Onboard) => api<unknown>("/api/profile", { method: "PUT", json: v }),
    onSuccess: () => qc.invalidateQueries(),
  });
}

export function useUpdateProfile() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: Partial<{ name: string; bio: string; employer: string; livesIn: string }>) =>
      api<Profile>("/api/profile", { method: "PUT", json: v }),
    onSuccess: () => qc.invalidateQueries(),
  });
}

export function useMarkNotificationsRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api<void>("/api/notifications/read", { method: "POST" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.notifications }),
  });
}

// -------------------------------------------------------------------------------------------- Messenger

export function useThreads() {
  return useQuery({ queryKey: keys.threads, queryFn: () => api<DmThread[]>("/api/dm/threads") });
}

export function useConversation(threadId: string) {
  return useQuery({
    queryKey: keys.conversation(threadId),
    queryFn: () => api<DmConversation>(`/api/dm/threads/${threadId}/messages`),
    enabled: Boolean(threadId),
  });
}

/** Opens (or finds) the thread with a resident. */
export function useOpenThread() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (handle: string) => api<DmThread>("/api/dm/threads", { json: { handle } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.threads }),
  });
}

/** R's message: in the conversation at once, then the server's copy. */
export function useSendDm(threadId: string, me: Actor | null | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (text: string) => api<DmMessage>(`/api/dm/threads/${threadId}/messages`, { json: { text } }),
    onMutate: async (text) => {
      await qc.cancelQueries({ queryKey: keys.conversation(threadId) });
      const temp: DmMessage = {
        id: `temp-${Date.now()}`,
        threadId,
        senderId: me?.id ?? "you",
        you: true,
        body: text,
        createdAt: Date.now(),
        readAt: null,
      };
      qc.setQueryData<DmConversation>(keys.conversation(threadId), (c) => (c ? { ...c, messages: [...c.messages, temp] } : c));
      return { tempId: temp.id };
    },
    onSuccess: (msg, _text, ctx) => {
      qc.setQueryData<DmConversation>(keys.conversation(threadId), (c) =>
        c ? { ...c, messages: c.messages.map((m) => (m.id === ctx?.tempId ? msg : m)) } : c,
      );
    },
    onError: (e, _text, ctx) => {
      qc.setQueryData<DmConversation>(keys.conversation(threadId), (c) =>
        c ? { ...c, messages: c.messages.filter((m) => m.id !== ctx?.tempId) } : c,
      );
      live.toast({
        id: `dm-failed-${Date.now()}`,
        text: e instanceof ApiError && e.status === 503 ? "The world is waking up. Try again in a few seconds." : "Your message didn't send. Try again.",
        postId: null,
      });
    },
    onSettled: () => qc.invalidateQueries({ queryKey: keys.threads }),
  });
}

export function useReadThread() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (threadId: string) => api<void>(`/api/dm/threads/${threadId}/read`, { method: "POST" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.threads }),
  });
}
