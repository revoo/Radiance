-- Live play (SPEC §12 M7): the recap card links each line to its event's post.
CREATE INDEX ix_post_event ON post(event_id);
