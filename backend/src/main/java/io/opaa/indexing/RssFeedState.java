package io.opaa.indexing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * The {@code ETag}/{@code Last-Modified} pair {@link RssFeedIndexingExecutor} last saw for a given
 * feed URL (ADR-0017), keyed by {@code (libraryId, feedUrl)} - lets a run send a conditional GET
 * and end in a single request when the feed itself has not changed. Its own table rather than a
 * {@link Document} row.
 *
 * <p>Keyed per library, not per URL alone: {@code feed_url} alone as unique key would let a library
 * deleted or reconfigured to a different {@code sourceUrl} leave its row behind, so a new library
 * later pointed at the same feed address would find that stale row, send a conditional GET, get a
 * {@code 304}, and end its very first run with zero documents - reported as success, not failure.
 * {@code libraryId} plus {@code onDelete: CASCADE} on {@code fk_rss_feed_state_library} means a
 * library's own deletion takes its state row with it, and a library that changes its {@code
 * sourceUrl} simply finds no row for the new address.
 */
@Entity
@Table(
    name = "rss_feed_state",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_rss_feed_state_library_feed_url",
            columnNames = {"library_id", "feed_url"}))
public class RssFeedState {

  @Id private UUID id;

  @Column(name = "library_id", nullable = false)
  private UUID libraryId;

  @Column(name = "feed_url", nullable = false, length = 2000)
  private String feedUrl;

  @Column(name = "etag", length = 500)
  private String etag;

  @Column(name = "last_modified", length = 200)
  private String lastModified;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected RssFeedState() {}

  public RssFeedState(UUID libraryId, String feedUrl, String etag, String lastModified) {
    this.id = UUID.randomUUID();
    this.libraryId = libraryId;
    this.feedUrl = feedUrl;
    this.etag = etag;
    this.lastModified = lastModified;
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getLibraryId() {
    return libraryId;
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
