-- Messenger (SPEC §5.11, §12 M8). How many of a thread's oldest messages its summary covers: packets
-- show the last 30 messages, and once 30 more have scrolled out of that window the summary is redone.
ALTER TABLE dm_thread ADD COLUMN summarized_count INTEGER NOT NULL DEFAULT 0;
-- R's thread list looks threads up by either side.
CREATE INDEX ix_dm_thread_b ON dm_thread(b_id);
