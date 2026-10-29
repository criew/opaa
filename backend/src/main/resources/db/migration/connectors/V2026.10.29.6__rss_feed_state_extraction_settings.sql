SET LOCAL lock_timeout = '5s';

-- The content selector and attachment profile a feed's entries were last completely processed
-- under; NULL for the factory defaults, so a row saved before per-library settings matches them.
-- A run whose library settings differ reads every entry of the feed again.
ALTER TABLE rss_feed_state ADD COLUMN extraction_settings text;
