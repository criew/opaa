package io.opaa.indexing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The {@code ETag}/{@code Last-Modified} pair {@link RssFeedIndexingExecutor} last saw for a given
 * feed URL (#467, ADR-0017), keyed by that URL - lets a run send a conditional GET and end in a
 * single request when the feed itself has not changed. See migration {@code
 * 025-create-rss-feed-state} for why this is its own table rather than a {@link Document} row.
 */
@Entity
@Table(name = "rss_feed_state")
public class RssFeedState {

  @Id private UUID id;

  @Column(name = "feed_url", nullable = false, length = 2000, unique = true)
  private String feedUrl;

  @Column(name = "etag", length = 500)
  private String etag;

  @Column(name = "last_modified", length = 200)
  private String lastModified;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected RssFeedState() {}

  public RssFeedState(String feedUrl, String etag, String lastModified) {
    this.id = UUID.randomUUID();
    this.feedUrl = feedUrl;
    this.etag = etag;
    this.lastModified = lastModified;
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getFeedUrl() {
    return feedUrl;
  }

  public String getEtag() {
    return etag;
  }

  public void setEtag(String etag) {
    this.etag = etag;
  }

  public String getLastModified() {
    return lastModified;
  }

  public void setLastModified(String lastModified) {
    this.lastModified = lastModified;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
