-- Simulation state the v1 schema had no home for (M4). SQLite dialect; must also run unchanged on D1.

-- Constraint tags an event put on an actor (budget_tight:30d, injured:knee, unemployed…). Tags that come
-- from an arc's current phase are derived from the arc and never stored here.
CREATE TABLE actor_constraint (
  actor_id TEXT NOT NULL REFERENCES actor(id),
  tag TEXT NOT NULL,                       -- the tag with its parameter: injured:knee
  since INTEGER NOT NULL,                  -- world time
  until INTEGER,                           -- world time it lapses; NULL = until an event removes it
  source_event_id TEXT,
  PRIMARY KEY (actor_id, tag)
);

-- Impression tags waiting out their hysteresis (SPEC §5.9): tag -> world time it first qualified.
ALTER TABLE relationship ADD COLUMN pending_tags_json TEXT NOT NULL DEFAULT '{}';

-- The world date interactions_today counts for (YYYY-MM-DD, world time zone).
ALTER TABLE actor_state ADD COLUMN interactions_day TEXT;

-- Cooldowns and caps read the recent log per actor and def.
CREATE INDEX ix_event_def ON event(def_id, actor_id, world_time);

-- The engine reloads the last few days of posts and comments (engagement, reply chains, airing checks).
CREATE INDEX ix_post_created ON post(created_at);
CREATE INDEX ix_comment_created ON comment(created_at);
