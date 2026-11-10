package io.opaa.indexing.document;

import io.opaa.format.DocumentService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The text recognition of a scanned document ({@code document_text_recognition}): its stored
 * recognition or its lock, at most one of both per document, deleted with the document by the
 * foreign key. Lives in the database of documents and chunks, so a restored backup restores all
 * three to the same moment. Every write is a single upsert or delete in its own statement.
 */
public class DocumentTextRecognitionStore {

  /** The content type of the documents text recognition reads. */
  static final String PDF_CONTENT_TYPE = "application/pdf";

  private final JdbcTemplate jdbcTemplate;

  public DocumentTextRecognitionStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * One row.
   *
   * @param content the stored recognition, {@code null} for a lock
   * @param blockedReason the German reason of a lock, {@code null} for a stored recognition
   */
  public record Entry(
      UUID documentId,
      String checksum,
      String connectionType,
      String configurationId,
      int pipelineVersion,
      Integer pageCount,
      String content,
      String blockedReason,
      Instant blockedAt) {

    public boolean blocked() {
      return blockedReason != null;
    }

    /**
     * Whether this row was made for the file {@code checksum} by the procedure {@code
     * connectionType}/{@code configurationId} at {@code pipelineVersion} - a lock outside it has
     * fallen.
     */
    public boolean madeFor(
        String checksum, String connectionType, String configurationId, int pipelineVersion) {
      return this.checksum.equals(checksum)
          && this.connectionType.equals(connectionType)
          && this.configurationId.equals(configurationId)
          && this.pipelineVersion == pipelineVersion;
    }
  }

  /** A locked document as the library's view lists it: no account, no content. */
  public record BlockedDocument(
      UUID documentId, String fileName, String reason, String connectionType, Instant blockedAt) {}

  public Optional<Entry> find(UUID documentId) {
    return jdbcTemplate
        .query(
            "SELECT document_id, checksum, connection_type, configuration_id, pipeline_version,"
                + " page_count, recognized_content, blocked_reason, blocked_at"
                + " FROM document_text_recognition WHERE document_id = ?",
            (rs, row) ->
                new Entry(
                    rs.getObject("document_id", UUID.class),
                    rs.getString("checksum"),
                    rs.getString("connection_type"),
                    rs.getString("configuration_id"),
                    rs.getInt("pipeline_version"),
                    (Integer) rs.getObject("page_count"),
                    rs.getString("recognized_content"),
                    rs.getString("blocked_reason"),
                    instant(rs.getTimestamp("blocked_at"))),
            documentId)
        .stream()
        .findFirst();
  }

  /** Stores the recognition of the file {@code checksum}, replacing a lock or an older one. */
  public void saveRecognition(
      UUID documentId,
      String checksum,
      String connectionType,
      String configurationId,
      int pipelineVersion,
      int pageCount,
      String content,
      Instant at) {
    upsert(
        documentId,
        checksum,
        connectionType,
        configurationId,
        pipelineVersion,
        pageCount,
        content,
        null,
        at);
  }

  /** Locks the file {@code checksum} for this procedure, replacing what was stored. */
  public void block(
      UUID documentId,
      String checksum,
      String connectionType,
      String configurationId,
      int pipelineVersion,
      Integer pageCount,
      String reason,
      Instant at) {
    upsert(
        documentId,
        checksum,
        connectionType,
        configurationId,
        pipelineVersion,
        pageCount,
        null,
        reason,
        at);
  }

  private void upsert(
      UUID documentId,
      String checksum,
      String connectionType,
      String configurationId,
      int pipelineVersion,
      Integer pageCount,
      String content,
      String reason,
      Instant at) {
    Timestamp timestamp = Timestamp.from(at);
    // A document deleted meanwhile inserts nothing: the row would only outlive it until the FK
    // refused it, so the insert is conditional on the document.
    jdbcTemplate.update(
        "INSERT INTO document_text_recognition (document_id, checksum, connection_type,"
            + " configuration_id, pipeline_version, page_count, recognized_content, recognized_at,"
            + " blocked_reason, blocked_at)"
            + " SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM documents WHERE id ="
            + " ?)"
            + " ON CONFLICT (document_id) DO UPDATE SET checksum = EXCLUDED.checksum,"
            + " connection_type = EXCLUDED.connection_type,"
            + " configuration_id = EXCLUDED.configuration_id,"
            + " pipeline_version = EXCLUDED.pipeline_version, page_count = EXCLUDED.page_count,"
            + " recognized_content = EXCLUDED.recognized_content,"
            + " recognized_at = EXCLUDED.recognized_at, blocked_reason = EXCLUDED.blocked_reason,"
            + " blocked_at = EXCLUDED.blocked_at",
        documentId,
        checksum,
        connectionType,
        configurationId,
        (short) pipelineVersion,
        pageCount,
        content,
        content == null ? null : timestamp,
        reason,
        reason == null ? null : timestamp,
        documentId);
  }

  /**
   * Releases the lock of {@code documentId}; a stored recognition is left alone.
   *
   * @return whether a lock was released
   */
  public boolean release(UUID documentId) {
    return jdbcTemplate.update(
            "DELETE FROM document_text_recognition WHERE document_id = ? AND blocked_reason IS NOT"
                + " NULL",
            documentId)
        > 0;
  }

  /**
   * The documents of {@code libraryId} with a lock that still holds: for the file as it is, of the
   * current pipeline version and, while a role is in force, made by its configuration {@code
   * configurationId} ({@code null}: any) - the same condition as the pending count.
   */
  public List<BlockedDocument> blocked(
      UUID libraryId, int pipelineVersion, String configurationId) {
    return jdbcTemplate.query(
        "SELECT r.document_id, d.file_name, r.blocked_reason, r.connection_type, r.blocked_at"
            + " FROM document_text_recognition r JOIN documents d ON d.id = r.document_id"
            + " WHERE d.library_id = ? AND r.blocked_reason IS NOT NULL"
            + " AND r.pipeline_version = ?"
            + " AND (d.checksum IS NULL OR r.checksum = d.checksum)"
            + " AND (CAST(? AS varchar) IS NULL OR r.configuration_id = ?)"
            + " ORDER BY d.file_name, d.id",
        (rs, row) ->
            new BlockedDocument(
                rs.getObject("document_id", UUID.class),
                rs.getString("file_name"),
                rs.getString("blocked_reason"),
                rs.getString("connection_type"),
                instant(rs.getTimestamp("blocked_at"))),
        libraryId,
        (short) pipelineVersion,
        configurationId,
        configurationId);
  }

  /**
   * Scans of {@code libraryId} rejected as without extractable text and not locked: what text
   * recognition still has to read.
   */
  public int pendingCount(UUID libraryId, int pipelineVersion, String configurationId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM documents d WHERE d.library_id = ? AND " + PENDING_CONDITION,
            Integer.class,
            libraryId,
            DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE,
            PDF_CONTENT_TYPE,
            (short) pipelineVersion,
            configurationId,
            configurationId);
    return count == null ? 0 : count;
  }

  /**
   * How many uploaded scans of every library wait for text recognition, and the seconds since the
   * oldest of them was uploaded ({@code 0} without one), by the database clock.
   */
  public UploadBacklog uploadBacklog(int pipelineVersion, String configurationId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) AS waiting,"
            + " coalesce(extract(epoch FROM clock_timestamp() - min(d.created_at)), 0) AS age"
            + " FROM documents d WHERE d.source_type = 'UPLOAD' AND "
            + PENDING_CONDITION,
        (rs, row) -> new UploadBacklog(rs.getLong("waiting"), rs.getDouble("age")),
        DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE,
        PDF_CONTENT_TYPE,
        (short) pipelineVersion,
        configurationId,
        configurationId);
  }

  /** The uploaded scans waiting for text recognition, without any document named. */
  public record UploadBacklog(long waiting, double oldestAgeSeconds) {}

  /**
   * Uploaded scans of every library still waiting for text recognition, in a stable order after
   * {@code afterId} ({@code null} from the start).
   */
  public List<UUID> pendingUploads(
      UUID afterId, int pipelineVersion, String configurationId, int limit) {
    return jdbcTemplate.queryForList(
        "SELECT d.id FROM documents d WHERE d.source_type = 'UPLOAD' AND "
            + PENDING_CONDITION
            + " AND (CAST(? AS uuid) IS NULL OR d.id > CAST(? AS uuid)) ORDER BY d.id LIMIT ?",
        UUID.class,
        DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE,
        PDF_CONTENT_TYPE,
        (short) pipelineVersion,
        configurationId,
        configurationId,
        afterId,
        afterId,
        limit);
  }

  /**
   * Rejected as without text, a PDF, and neither locked nor already read without text by this
   * procedure for this very file.
   */
  private static final String PENDING_CONDITION =
      "d.status = 'FAILED' AND d.error_message = ? AND d.content_type = ?"
          + " AND NOT EXISTS (SELECT 1 FROM document_text_recognition r"
          + " WHERE r.document_id = d.id"
          + " AND r.pipeline_version = ?"
          + " AND (CAST(? AS varchar) IS NULL OR r.configuration_id = ?)"
          + " AND (d.checksum IS NULL OR r.checksum = d.checksum))";

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
