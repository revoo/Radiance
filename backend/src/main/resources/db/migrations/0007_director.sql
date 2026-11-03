-- the Director's time controls (SPEC §5.16): world time held while paused
ALTER TABLE world ADD COLUMN paused_at INTEGER;
-- a storyline's timeline in the console
CREATE INDEX ix_event_arc ON event(arc_id, world_time);
-- the inspector's mood chart: a resident's mood at each nightly snapshot
ALTER TABLE meter_snapshot ADD COLUMN mood REAL;
