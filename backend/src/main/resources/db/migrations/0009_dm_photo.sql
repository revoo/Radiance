-- A photo in a DM, and what the camera sees, which the sender remembers.
ALTER TABLE dm_message ADD COLUMN media_id TEXT;
ALTER TABLE dm_message ADD COLUMN gist TEXT;
