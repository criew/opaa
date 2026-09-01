package io.opaa.searchadmin;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the per-library {@code documents} aggregates of one organization in a single grouped query
 * - never one query per library, which would put a round trip per row of the status table onto an
 * administrative page load.
 *
 * <p>All counts come from one statement rather than several: under {@code READ COMMITTED} a running
 * indexing job could otherwise be counted as pending by one statement and as indexed by the next,
 * producing a status row that never existed.
 *
 * <p>Deliberately read-only and its own component rather than a method on {@code
 * io.opaa.indexing.DocumentRepository}: this package observes the indexing bestand, it does not
 * extend the indexing domain.
 */
@Component
public class LibraryDocumentStatsReader {

  /**
   * At or below this chunk count an indexed document counts as auffällig - the same default {@code
   * LowChunkDocumentAuditService} uses for its own listing, so the number on the status page and
   * the number of rows behind it agree.
   */
  static final int LOW_CHUNK_THRESHOLD = 0;

  private static final String SQL =
      """
      SELECT library_id,
             count(*) AS document_count,
             count(*) FILTER (WHERE status = 'INDEXED') AS indexed_document_count,
             count(*) FILTER (WHERE status = 'PENDING') AS pending_document_count,
             count(*) FILTER (WHERE status = 'FAILED') AS failed_document_count,
             count(*) FILTER (WHERE status = 'INDEXED' AND chunk_count <= ?)
               AS low_chunk_document_count,
             COALESCE(sum(chunk_count), 0) AS chunk_count,
             max(indexed_at) AS last_indexed_at
      FROM documents
      WHERE organization_id = ? AND library_id IS NOT NULL
      GROUP BY library_id
      """;

  private final JdbcTemplate jdbcTemplate;

  public LibraryDocumentStatsReader(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** The aggregates by library id; a library without any document is simply absent from the map. */
  public Map<UUID, LibraryDocumentStats> statsForOrganization(UUID organizationId) {
    Map<UUID, LibraryDocumentStats> byLibrary = new HashMap<>();
    jdbcTemplate.query(
        SQL,
        rs -> {
          Timestamp lastIndexedAt = rs.getTimestamp("last_indexed_at");
          UUID libraryId = (UUID) rs.getObject("library_id");
          byLibrary.put(
              libraryId,
              new LibraryDocumentStats(
                  libraryId,
                  rs.getLong("document_count"),
                  rs.getLong("indexed_document_count"),
                  rs.getLong("pending_document_count"),
                  rs.getLong("failed_document_count"),
                  rs.getLong("low_chunk_document_count"),
                  rs.getLong("chunk_count"),
                  lastIndexedAt == null ? null : lastIndexedAt.toInstant()));
        },
        LOW_CHUNK_THRESHOLD,
        organizationId);
    return byLibrary;
  }
}
