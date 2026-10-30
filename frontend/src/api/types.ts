// What the backend returns (SPEC §7; backend api/Views.java). Times are epoch ms in world time.

export type Actor = {
  id: string;
  handle: string;
  name: string;
  you: boolean;
  occupation: string | null;
  livesIn: string | null;
};

export const REACTION_KINDS = ["LIKE", "LOVE", "CARE", "HAHA", "WOW", "SAD", "ANGRY"] as const;
export type ReactionKind = (typeof REACTION_KINDS)[number];

export type Reactions = {
  total: number;
  top: ReactionKind[];
  byKind: Partial<Record<ReactionKind, number>>;
  mine?: ReactionKind | null;
  recent: string[];
};

export type Typing = { actorId: string; name: string };

export type Comment = {
  id: string;
  postId: string;
  parentId: string | null;
  author: Actor;
  createdAt: number;
  body: string;
  reactions: Reactions;
  replies: Comment[];
};

export type Post = {
  id: string;
  author: Actor;
  createdAt: number;
  kind: string;
  audience: "FRIENDS" | "PUBLIC";
  body: string;
  reactions: Reactions;
  commentCount: number;
  preview: Comment[];
  typing: Typing[];
};

export type Page<T> = { items: T[]; nextCursor: string | null };

export type Me = {
  username: string;
  worldReady: boolean;
  onboarded: boolean;
  actor: Actor | null;
  networkName: string;
};

export type World = {
  name: string;
  networkName: string;
  timezone: string;
  worldTime: number;
  committedUntil: number;
  scriptedUntil: number;
  paused: boolean;
  backfilling: boolean;
};

export type Status = { worldTime: number; scriptedUntil: number; backfilling: boolean; sessionActive: boolean };

export type RecapLine = {
  eventId: string;
  line: string;
  importance: number;
  at: number;
  postId: string | null;
  actor: Actor | null;
};

export type Recap = { since: number | null; lines: RecapLine[] };

export type Person = {
  actor: Actor;
  bio: string | null;
  employer: string | null;
  hometown: string | null;
  relationshipStatus: string | null;
  birthday: string | null;
  relationship: string | null;
  friend: boolean;
  mutualFriends: number;
  friends: Actor[];
};

export type Notification = {
  id: string;
  kind: string;
  actors: Actor[];
  refType: string | null;
  refId: string | null;
  postId: string | null;
  snippet: string | null;
  createdAt: number;
  read: boolean;
};

export type Notifications = { items: Notification[]; unread: number };

export type Profile = {
  id: string;
  handle: string;
  displayName: string;
  bio: string | null;
  employer: string | null;
  livesIn: string | null;
  occupation: string | null;
  birthDate: string | null;
};

export type Backstory = "NEWCOMER" | "REGULAR" | "LOCAL";

export type Onboard = {
  name: string;
  bio: string;
  employer: string;
  livesIn: string;
  circles: string[];
  interests: string[];
  backstory: Backstory;
};

export type PacerMonitor = {
  sessionId: string | null;
  sessionStartedAt: number | null;
  lastHeartbeat: number | null;
  typing: { key: string; postId: string; actorId: string; name: string; since: number }[];
  pacer: {
    now: number;
    pending: number;
    commentsLastMinute: number;
    reactionsLastMinute: number;
    notificationsLastMinute: number;
    maxCommentsPerMinute: number;
    maxNotificationsPerMinute: number;
    breathingUntil: number | null;
    lastSeen: number | null;
    lastHook: number | null;
    bursts: {
      trigger: string;
      start: number;
      firstReaction: number | null;
      firstComment: number | null;
      lastRelease: number | null;
      reactions: number;
      comments: number;
      dropped: number;
      unpaced: number;
    }[];
  } | null;
};

/** A direct message. On R's own message, `readAt` is the resident's Seen. */
export type DmMessage = {
  id: string;
  threadId: string;
  senderId: string;
  you: boolean;
  body: string;
  createdAt: number;
  readAt: number | null;
};

export type DmThread = { id: string; with: Actor; lastMessage: DmMessage | null; unread: number; typing: boolean };

export type DmConversation = { thread: DmThread; messages: DmMessage[]; typing: Typing[] };
