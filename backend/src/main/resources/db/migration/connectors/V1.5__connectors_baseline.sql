-- Module connectors: the RSS connector's per-library feed state.

CREATE TABLE rss_feed_state (
    id uuid NOT NULL,
    feed_url character varying(2000) NOT NULL,
    etag text,
    last_modified text,
    updated_at timestamp with time zone NOT NULL,
    library_id uuid NOT NULL
);

ALTER TABLE ONLY rss_feed_state
    ADD CONSTRAINT rss_feed_state_pkey PRIMARY KEY (id);

ALTER TABLE ONLY rss_feed_state
    ADD CONSTRAINT uk_rss_feed_state_library_feed_url UNIQUE (library_id, feed_url);

ALTER TABLE ONLY rss_feed_state
    ADD CONSTRAINT fk_rss_feed_state_library FOREIGN KEY (library_id) REFERENCES knowledge_libraries(id) ON DELETE CASCADE;
