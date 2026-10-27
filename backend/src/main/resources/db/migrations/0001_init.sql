-- Radiance schema v1 (SPEC §4.3). SQLite dialect; must also run unchanged on D1.
--
-- Times are INTEGER epoch milliseconds UTC. Content and world-state columns are in WORLD time
-- (wall time + world.time_offset_ms). Operational columns are in WALL time (ADR 0010):
-- world.lease_until, session_log.*, job.run_after/locked_at/created_at, budget_window.resets_at/
-- closed_until, batch_run.started_at/finished_at, llm_call.created_at, headline.fetched_at,
-- weather_hour.fetched_at, schema_migrations.applied_at.
--
-- Deviations from the spec's listing (agreed with R, ADR 0013): render_status CHECKs on comment and
-- dm_message; ux_job_active also covers WAITING; two read-path indexes (friend requests to R, the
-- DM thread list).

CREATE TABLE world (
  id TEXT PRIMARY KEY,                       -- single row
  name TEXT NOT NULL, network_name TEXT NOT NULL,
  timezone TEXT NOT NULL, seed INTEGER NOT NULL,
  committed_until INTEGER NOT NULL,          -- everything due up to here has aired (§5.1)
  scripted_until INTEGER NOT NULL,           -- the script reaches this far
  time_offset_ms INTEGER NOT NULL DEFAULT 0, -- world time = wall time + offset
  paused INTEGER NOT NULL DEFAULT 0,
  tension REAL NOT NULL DEFAULT 0,           -- storyteller drama meter, 0..100
  last_session_at INTEGER,                   -- R's last activity; drives idle decay
  lease_owner TEXT, lease_until INTEGER,     -- writer lease (§4.2); lease_until is wall time
  lease_epoch INTEGER NOT NULL DEFAULT 0,    -- fencing token
  settings_json TEXT NOT NULL,               -- live-tuned knobs (override application.yml)
  created_at INTEGER NOT NULL
);
CREATE TABLE lease_fence (id INTEGER PRIMARY KEY CHECK (id = 1), ok INTEGER NOT NULL);
CREATE TABLE session_log (id TEXT PRIMARY KEY, started_at INTEGER NOT NULL, ended_at INTEGER);
CREATE INDEX ix_session_started ON session_log(started_at);

CREATE TABLE actor (                         -- residents (BOT) and R (USER)
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('BOT','USER')),
  handle TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL,
  avatar_media_id TEXT, cover_media_id TEXT,
  birth_date TEXT, gender TEXT,              -- gender: M | F (optional for R, §5.9)
  occupation TEXT, employer TEXT, hometown TEXT, current_city TEXT,
  relationship_status TEXT, bio TEXT,
  circles_json TEXT NOT NULL DEFAULT '[]',   -- roster circles (§6.2); R picks his at onboarding
  created_at INTEGER NOT NULL
);

CREATE TABLE persona (                       -- bots only; the slow-changing core
  actor_id TEXT PRIMARY KEY REFERENCES actor(id),
  traits_json TEXT NOT NULL,     -- baselines 0..100 (§5.2)
  quirks_json TEXT NOT NULL,     -- quirk def ids
  interests_json TEXT NOT NULL,  -- topic -> weight 0..1
  politics_json TEXT NOT NULL,   -- {economic, social, interest, issues}
  biases_json TEXT NOT NULL,     -- everyday and strong biases: target, strength, style (§5.2)
  taste_json TEXT NOT NULL,      -- appraisal feature -> weight (how they judge others' content)
  temperament_json TEXT NOT NULL,-- preset, baseline, gains and bounds for their like-meters (§5.2)
  voice_json TEXT NOT NULL,      -- voice card + samples (§5.2)
  routine_json TEXT NOT NULL,    -- chronotype, schedule, online intensity, posting rate, attention budget
  values_json TEXT NOT NULL, fears_json TEXT NOT NULL, desires_json TEXT NOT NULL,
  off_network_json TEXT NOT NULL,-- family, kids, pets etc. who aren't on the network (text only)
  identity_json TEXT NOT NULL,   -- heritage, faith and observance from the roster (§6.2)
  backstory TEXT NOT NULL,
  life_summary TEXT,             -- rolling "lately" summary maintained by compaction
  pinned INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE actor_state (
  actor_id TEXT PRIMARY KEY REFERENCES actor(id),
  mood REAL NOT NULL,            -- -1..1
  energy REAL NOT NULL,          -- 0..1
  stress REAL NOT NULL,          -- 0..1
  partner_satisfaction REAL,     -- 0..1, residents with a partner only; drifts like mood (§5.2)
  trait_offsets_json TEXT NOT NULL,  -- bounded drift from baseline
  location TEXT NOT NULL,        -- HOME | WORK | OUT | TRAVELING:<place>
  interactions_today INTEGER NOT NULL DEFAULT 0,
  last_feed_check_at INTEGER,
  updated_at INTEGER NOT NULL
);

CREATE TABLE relationship (                  -- directed: how from_id sees to_id
  from_id TEXT NOT NULL REFERENCES actor(id),
  to_id TEXT NOT NULL REFERENCES actor(id),
  kind TEXT NOT NULL,            -- STRANGER|ACQUAINTANCE|FRIEND|CLOSE_FRIEND|FAMILY|COWORKER|PARTNER|EX|RIVAL
  affinity REAL NOT NULL,        -- -100..100; the like-meter (§5.3)
  trust REAL NOT NULL,           -- 0..100
  attraction REAL NOT NULL,      -- 0..100
  familiarity REAL NOT NULL,     -- 0..100
  respect REAL NOT NULL,         -- -100..100
  chemistry REAL NOT NULL,       -- fixed at creation, -1..1: some pairs just click
  tags_json TEXT NOT NULL DEFAULT '[]',  -- e.g. ["debate_rival:economics","crush","finds_pretentious"]
  summary TEXT,                  -- 1-3 sentences, refreshed by compaction
  connected INTEGER NOT NULL DEFAULT 0,  -- friends on the network; kept symmetric
  last_interaction_at INTEGER,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (from_id, to_id)
);
CREATE TABLE meter_snapshot (                -- nightly copy of each resident's like-meter for R (7-day trends)
  day TEXT NOT NULL,             -- world date, YYYY-MM-DD
  actor_id TEXT NOT NULL REFERENCES actor(id),
  affinity REAL NOT NULL,
  PRIMARY KEY (day, actor_id)
);
CREATE INDEX ix_rel_to ON relationship(to_id);

CREATE TABLE arc (                           -- an ongoing storyline instance (committed state)
  id TEXT PRIMARY KEY, def_id TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE','RESOLVED','ABANDONED')),
  phase TEXT NOT NULL,
  visibility TEXT NOT NULL CHECK (visibility IN ('PUBLIC','PRIVATE','SECRET')),
  started_at INTEGER NOT NULL, phase_entered_at INTEGER NOT NULL,
  next_transition_at INTEGER, last_beat_at INTEGER,
  data_json TEXT NOT NULL,       -- def-specific: cause, partner, entity ids...
  summary TEXT NOT NULL,         -- engine-maintained one-liner
  ended_at INTEGER, end_event_id TEXT
);
CREATE INDEX ix_arc_status ON arc(status, next_transition_at);
CREATE TABLE arc_participant (
  arc_id TEXT NOT NULL REFERENCES arc(id), actor_id TEXT NOT NULL REFERENCES actor(id),
  role TEXT NOT NULL, PRIMARY KEY (arc_id, actor_id)
);
CREATE INDEX ix_arcp_actor ON arc_participant(actor_id);

CREATE TABLE entity (                        -- things that matter to stories: the car, the house, the dog
  id TEXT PRIMARY KEY, owner_id TEXT REFERENCES actor(id),
  kind TEXT NOT NULL,            -- CAR|HOME|PET|PROJECT|PLACE|BUSINESS|OBJECT
  name TEXT NOT NULL,
  visual_description TEXT,       -- canonical; reused verbatim in every image prompt
  image_seed INTEGER,
  attributes_json TEXT NOT NULL,
  status TEXT NOT NULL,          -- OWNED|SOLD|BROKEN|FINISHED|DECEASED...
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX ix_entity_owner ON entity(owner_id);

CREATE TABLE fact (                          -- canon for anything not modeled elsewhere
  id TEXT PRIMARY KEY, subject_id TEXT NOT NULL,   -- actor or entity id
  statement TEXT NOT NULL,       -- "bought a used tile saw for $80"
  weight TEXT NOT NULL CHECK (weight IN ('MAJOR','MINOR')),
  source TEXT NOT NULL CHECK (source IN ('ENGINE','GENESIS','PROSE','USER')),
  status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('PENDING','ACTIVE','VOID')),
                                 -- PENDING until the item that introduced it airs; VOID if that item is dropped
  visibility TEXT NOT NULL DEFAULT 'PUBLIC',
  arc_id TEXT, valid_from INTEGER NOT NULL, valid_to INTEGER,
  origin_ref TEXT                -- post/comment it came from
);
CREATE INDEX ix_fact_subject ON fact(subject_id, valid_from);

CREATE TABLE knowledge (                     -- who knows PRIVATE/SECRET things; PUBLIC = everyone
  subject_type TEXT NOT NULL,    -- ARC|FACT|EVENT
  subject_id TEXT NOT NULL,
  actor_id TEXT NOT NULL REFERENCES actor(id),
  since INTEGER NOT NULL,
  PRIMARY KEY (subject_type, subject_id, actor_id)
);

CREATE TABLE event (                         -- append-only log of everything that happens
  id TEXT PRIMARY KEY, world_time INTEGER NOT NULL, type TEXT NOT NULL,
  def_id TEXT,                   -- event or arc def; airing re-checks its conditions
  actor_id TEXT, target_id TEXT, arc_id TEXT,
  importance INTEGER NOT NULL,   -- 1..10
  visibility TEXT NOT NULL,
  source TEXT NOT NULL,          -- STORYTELLER|ARC|ROUTINE|NEWS|CALENDAR|USER|DIRECTOR
  air_status TEXT NOT NULL CHECK (air_status IN ('SCRIPTED','AIRED','DROPPED')),
  payload_json TEXT NOT NULL,    -- participants and recorded effects
  recap_line TEXT                -- pre-rendered "while you were away" line (importance >= 6)
);
CREATE INDEX ix_event_time ON event(world_time);
CREATE INDEX ix_event_actor ON event(actor_id, world_time);
CREATE INDEX ix_event_script ON event(world_time) WHERE air_status = 'SCRIPTED';

CREATE TABLE scheduled_intent (              -- planned actions past the end of the current script (§5.1)
  id TEXT PRIMARY KEY, due_at INTEGER NOT NULL,
  actor_id TEXT NOT NULL REFERENCES actor(id),
  kind TEXT NOT NULL,            -- REACT|COMMENT|REPLY|POST|DM|FRIEND_REQUEST|ARC_BEAT...
  target_type TEXT, target_id TEXT,
  payload_json TEXT NOT NULL,    -- stance, beats, ...
  status TEXT NOT NULL CHECK (status IN ('PENDING','DONE','CANCELLED')),
  cause_event_id TEXT
);
CREATE INDEX ix_intent_due ON scheduled_intent(status, due_at);
CREATE INDEX ix_intent_target ON scheduled_intent(target_type, target_id, status);

CREATE TABLE post (
  id TEXT PRIMARY KEY, author_id TEXT NOT NULL REFERENCES actor(id),
  created_at INTEGER NOT NULL,   -- air time
  kind TEXT NOT NULL,            -- STATUS|PHOTO|LINK|LIFE_EVENT|CHECKIN
  audience TEXT NOT NULL,        -- FRIENDS|PUBLIC
  origin TEXT NOT NULL,          -- SCRIPT|LIVE|BACKFILL|USER
  air_status TEXT NOT NULL CHECK (air_status IN ('SCRIPTED','AIRED','DROPPED')),
  body TEXT,                     -- null until rendered for bots; immediate for R
  render_status TEXT NOT NULL CHECK (render_status IN ('PLANNED','RENDERED','FAILED','DROPPED')),
  brief_json TEXT,               -- the engine's brief for the renderer (§5.10)
  appraisal_json TEXT,           -- R's posts only (§5.9)
  media_id TEXT, headline_id TEXT, arc_id TEXT, event_id TEXT,
  importance INTEGER NOT NULL,
  fidelity TEXT NOT NULL,        -- FULL|REDUCED|SUMMARY
  reaction_counts_json TEXT NOT NULL DEFAULT '{}',
  comment_count INTEGER NOT NULL DEFAULT 0     -- aired, rendered comments only
);
CREATE INDEX ix_post_feed ON post(created_at) WHERE air_status = 'AIRED' AND render_status = 'RENDERED';
CREATE INDEX ix_post_author ON post(author_id, created_at);
CREATE INDEX ix_post_script ON post(created_at) WHERE air_status = 'SCRIPTED';

CREATE TABLE comment (
  id TEXT PRIMARY KEY, post_id TEXT NOT NULL REFERENCES post(id),
  parent_id TEXT REFERENCES comment(id),       -- UI shows one level of nesting
  author_id TEXT NOT NULL REFERENCES actor(id),
  created_at INTEGER NOT NULL,   -- air time
  origin TEXT NOT NULL,          -- SCRIPT|LIVE|BACKFILL|USER
  air_status TEXT NOT NULL CHECK (air_status IN ('SCRIPTED','AIRED','DROPPED')),
  body TEXT, gist TEXT,          -- gist: <=15-word summary from the renderer, feeds memories
  render_status TEXT NOT NULL CHECK (render_status IN ('PLANNED','RENDERED','FAILED','DROPPED')),
  brief_json TEXT, appraisal_json TEXT,
  reaction_counts_json TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX ix_comment_post ON comment(post_id, created_at);
CREATE INDEX ix_comment_script ON comment(created_at) WHERE air_status = 'SCRIPTED';

CREATE TABLE reaction (
  target_type TEXT NOT NULL CHECK (target_type IN ('POST','COMMENT')),
  target_id TEXT NOT NULL, actor_id TEXT NOT NULL REFERENCES actor(id),
  kind TEXT NOT NULL,            -- LIKE|LOVE|CARE|HAHA|WOW|SAD|ANGRY
  created_at INTEGER NOT NULL,   -- air time
  air_status TEXT NOT NULL CHECK (air_status IN ('SCRIPTED','AIRED','DROPPED')),
  PRIMARY KEY (target_type, target_id, actor_id)
);
CREATE INDEX ix_reaction_script ON reaction(created_at) WHERE air_status = 'SCRIPTED';

CREATE TABLE friend_request (
  id TEXT PRIMARY KEY, from_id TEXT NOT NULL, to_id TEXT NOT NULL,
  created_at INTEGER NOT NULL, status TEXT NOT NULL, resolved_at INTEGER
);
CREATE INDEX ix_friend_request_to ON friend_request(to_id, status);

CREATE TABLE dm_thread (
  id TEXT PRIMARY KEY, a_id TEXT NOT NULL, b_id TEXT NOT NULL,
  summary TEXT, last_message_at INTEGER, UNIQUE (a_id, b_id)
);
CREATE INDEX ix_dm_thread_last ON dm_thread(last_message_at);
CREATE TABLE dm_message (
  id TEXT PRIMARY KEY, thread_id TEXT NOT NULL REFERENCES dm_thread(id),
  sender_id TEXT NOT NULL, created_at INTEGER NOT NULL,      -- air time
  air_status TEXT NOT NULL CHECK (air_status IN ('SCRIPTED','AIRED','DROPPED')),
  body TEXT,
  render_status TEXT NOT NULL CHECK (render_status IN ('PLANNED','RENDERED','FAILED','DROPPED')),
  brief_json TEXT, appraisal_json TEXT, read_at INTEGER
);
CREATE INDEX ix_dm_thread ON dm_message(thread_id, created_at);
CREATE INDEX ix_dm_script ON dm_message(created_at) WHERE air_status = 'SCRIPTED';

CREATE TABLE notification (                  -- recipient is always R in V1; created when its item airs
  id TEXT PRIMARY KEY, kind TEXT NOT NULL, actor_id TEXT,
  ref_type TEXT, ref_id TEXT, created_at INTEGER NOT NULL, read_at INTEGER
);
CREATE INDEX ix_notif_time ON notification(created_at);

CREATE TABLE memory (                        -- created when the event it remembers airs
  id TEXT PRIMARY KEY, owner_id TEXT NOT NULL REFERENCES actor(id),
  world_time INTEGER NOT NULL,
  kind TEXT NOT NULL,            -- EPISODE|PERIOD_SUMMARY
  text TEXT NOT NULL, importance INTEGER NOT NULL,
  participants_json TEXT NOT NULL, tags_json TEXT NOT NULL,
  arc_id TEXT, event_id TEXT, compacted INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX ix_memory_owner ON memory(owner_id, compacted, world_time);

CREATE TABLE headline (
  id TEXT PRIMARY KEY, url TEXT NOT NULL UNIQUE, source TEXT NOT NULL,
  title TEXT NOT NULL, snippet TEXT,          -- snippet <= 300 chars from the feed; never full articles
  published_at INTEGER NOT NULL, fetched_at INTEGER NOT NULL,
  region TEXT,                   -- LOCAL|NATIONAL|WORLD
  topics_json TEXT, controversy REAL, salience REAL,
  sensitivity TEXT,              -- NORMAL|SENSITIVE|TRAGIC
  axis_signal_json TEXT, classified_at INTEGER
);
CREATE INDEX ix_headline_pub ON headline(published_at);

CREATE TABLE weather_hour (                  -- one row per hour of world time (§5.12)
  hour INTEGER PRIMARY KEY,      -- epoch ms at the top of the hour
  temp_f REAL NOT NULL, feels_like_f REAL NOT NULL,
  precip_in REAL NOT NULL, snow_in REAL NOT NULL, wind_mph REAL NOT NULL,
  wmo_code INTEGER NOT NULL,     -- WMO weather code (Appendix C)
  kind TEXT NOT NULL CHECK (kind IN ('FORECAST','PAST','FAKE')),
  fetched_at INTEGER NOT NULL
);

CREATE TABLE media (
  id TEXT PRIMARY KEY, kind TEXT NOT NULL,
  origin TEXT NOT NULL,          -- UPLOAD|GENERATED
  storage_key TEXT, thumb_key TEXT, mime TEXT, width INTEGER, height INTEGER, bytes INTEGER,
  prompt TEXT, seed INTEGER, entity_id TEXT,
  description TEXT,              -- alt text: generation prompt or vision appraisal
  status TEXT NOT NULL,          -- PENDING|READY|DEFERRED|FAILED
  created_at INTEGER NOT NULL
);

CREATE TABLE job (                           -- durable work queue for batch and background work
  id TEXT PRIMARY KEY, kind TEXT NOT NULL, ref_id TEXT NOT NULL,
  priority INTEGER NOT NULL,     -- lower runs first
  status TEXT NOT NULL CHECK (status IN ('PENDING','RUNNING','WAITING','DONE','FAILED')),
  attempts INTEGER NOT NULL DEFAULT 0, run_after INTEGER NOT NULL,   -- WAITING: parked until a budget window resets
  wait_reason TEXT,              -- the budget_window a WAITING job is parked on (§5.15)
  locked_at INTEGER, last_error TEXT, created_at INTEGER NOT NULL
);
CREATE TABLE budget_window (                 -- spend and quota use per window (§5.15)
  id TEXT PRIMARY KEY,           -- e.g. "usd:daily", "usd:monthly", "gemini:requests:day"
  used REAL NOT NULL,            -- dollars, tokens or requests, per the window's unit
  limit_value REAL NOT NULL,
  resets_at INTEGER NOT NULL,    -- the next reset
  closed_until INTEGER           -- set when a provider's 429 or quota error says to wait
);
CREATE UNIQUE INDEX ux_job_active ON job(kind, ref_id) WHERE status IN ('PENDING','RUNNING','WAITING');
CREATE INDEX ix_job_ready ON job(status, priority, run_after);

CREATE TABLE batch_run (                     -- one row per batch attempt
  id TEXT PRIMARY KEY, kind TEXT NOT NULL,   -- NIGHTLY|BACKFILL|MANUAL
  started_at INTEGER NOT NULL, finished_at INTEGER,
  status TEXT NOT NULL CHECK (status IN ('RUNNING','DONE','PARTIAL','DEFERRED','FAILED')),
  script_from INTEGER, script_to INTEGER, fidelity TEXT,
  items_planned INTEGER, items_rendered INTEGER, items_dropped INTEGER, images INTEGER,
  cost_usd REAL, rows_written INTEGER,
  report_json TEXT, error TEXT
);
CREATE INDEX ix_batch_started ON batch_run(started_at);

CREATE TABLE llm_call (
  id TEXT PRIMARY KEY, purpose TEXT NOT NULL,
  provider TEXT NOT NULL, alias TEXT NOT NULL,   -- the model alias from config (§9)
  model TEXT NOT NULL,                           -- the provider's model id at call time
  attempt INTEGER NOT NULL DEFAULT 1,            -- 2+ = a retry or a fallback model
  outcome TEXT NOT NULL,         -- OK|REFUSED|TRUNCATED|INVALID_OUTPUT|RATE_LIMITED|ERROR|TIMEOUT
  input_tokens INTEGER, cache_read_tokens INTEGER, cache_write_tokens INTEGER,
  output_tokens INTEGER, reasoning_tokens INTEGER,
  cost_usd REAL, latency_ms INTEGER, error TEXT,
  prompt_ref TEXT,               -- stored prompt/response when radiance.llm.store-prompts=true
  created_at INTEGER NOT NULL
);
CREATE INDEX ix_llm_time ON llm_call(created_at);
