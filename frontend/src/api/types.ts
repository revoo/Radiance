// What the backend returns (SPEC §7; backend api/Views.java). Times are epoch ms in world time.

export type Actor = {
  id: string;
  handle: string;
  name: string;
  you: boolean;
  occupation: string | null;
  livesIn: string | null;
  avatarUrl?: string | null;
};

export type Media = {
  id: string;
  url: string | null;
  thumbUrl: string | null;
  description: string | null;
  status: string;
  width: number | null;
  height: number | null;
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
  link?: PostLink | null;
  media?: Media | null;
};

/** A news take's link card. The url is null unless it's a real web link. */
export type PostLink = { url: string | null; source: string; title: string };

export type LiveCamera = { id: string; name: string; takenAt: number };

export type TalkedStory = {
  title: string;
  source: string;
  postId: string;
  posts: number;
  comments: number;
  region: string;
  controversy: number;
  sensitivity: string;
  people: Actor[];
};

export type Rail = {
  online?: string[];
  /** A line for the contacts list: "at work", "active 3m ago". */
  presence?: Record<string, string>;
  birthdays?: Actor[];
  weather: {
    hour: number;
    tempF: number;
    feelsLikeF: number;
    windMph: number;
    wmo: number;
    highF: number;
    lowF: number;
    sky: string;
    description: string;
    next: { hour: number; tempF: number; wmo: number }[];
  } | null;
  talkedAbout: TalkedStory[];
};

export type Page<T> = { items: T[]; nextCursor: string | null };

export type Relationship = { actor: Actor; meter: number; trend: number | null; kind: string; friend: boolean };

export type Me = {
  likeMeters?: boolean;
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

export type Status = { worldTime: number; scriptedUntil: number; backfilling: boolean; sessionActive: boolean; paused: boolean };

export type RecapLine = {
  eventId: string;
  line: string;
  importance: number;
  at: number;
  postId: string | null;
  actor: Actor | null;
};

export type Recap = { since: number | null; lines: RecapLine[] };

export type FriendRequest = { id: string; from: Actor; createdAt: number };

export type Person = {
  requestId?: string | null;
  request?: "SENT" | "RECEIVED" | null;
  meter?: number | null;
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

export type PacerStep = { at: number; state: "PLANNED" | "HELD" | "RELEASED" | "DROPPED" | "BACKGROUND"; kind: "REACTION" | "COMMENT"; actorId: string };

export type PacerMonitor = {
  sessionId: string | null;
  sessionStartedAt: number | null;
  lastHeartbeat: number | null;
  typing: { key: string; postId: string; actorId: string; name: string; since: number }[];
  pacer: {
    steps?: PacerStep[];
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
  media?: Media | null;
};

export type DmThread = { id: string; with: Actor; lastMessage: DmMessage | null; unread: number; typing: boolean };

export type DmConversation = { thread: DmThread; messages: DmMessage[]; typing: Typing[] };
