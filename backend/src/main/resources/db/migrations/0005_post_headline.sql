-- the rail's faces on a story (and links from a headline to its takes)
CREATE INDEX ix_post_headline ON post(headline_id, created_at);
