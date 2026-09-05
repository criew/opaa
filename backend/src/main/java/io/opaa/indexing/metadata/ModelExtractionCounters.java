package io.opaa.indexing.metadata;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Persists the Zählwerk of the model-backed extraction and the discarded values behind it (#1073).
 * The counting row is upserted with additive increments, so two concurrent ingests of the same
 * library cannot lose a call; the rejection log is capped per library ({@link #REJECTION_CAP}) and
 * rotates, because its purpose is the current confidence distribution, not a permanent archive of
 * every value the model ever proposed.
 */
@Component
public class ModelExtractionCounters {

  /** Newest rejections kept per library - enough for a distribution, bounded for a database. */
  static final int REJECTION_CAP = 1000;

  /**
   * Every so many calls of a library the rejection log is rotated back to {@link #REJECTION_CAP}.
   */
  static final int ROTATION_INTERVAL = 100;

  private final JdbcTemplate jdbcTemplate;

  public ModelExtractionCounters(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Records one model call of {@code libraryId} with everything {@code tally} collected. Called
   * after the call returned or failed, never before - a call that was never made is not counted.
   */
  public void record(UUID libraryId, UUID documentId, ModelExtractionTally tally) {
    Long calls =
        jdbcTemplate.queryForObject(
            "INSERT INTO metadata_model_extraction_stats (library_id, calls, accepted_values,"
                + " rejected_below_threshold, rejected_outside_vocabulary, failures,"
                + " rejected_pool_full, keywords_assigned, last_call_at)"
                + " VALUES (?, 1, ?, ?, ?, ?, ?, ?, now())"
                + " ON CONFLICT (library_id) DO UPDATE SET"
                + " calls = metadata_model_extraction_stats.calls + 1,"
                + " accepted_values = metadata_model_extraction_stats.accepted_values + EXCLUDED.accepted_values,"
                + " rejected_below_threshold = metadata_model_extraction_stats.rejected_below_threshold"
                + " + EXCLUDED.rejected_below_threshold,"
                + " rejected_outside_vocabulary ="
                + " metadata_model_extraction_stats.rejected_outside_vocabulary +"
                + " EXCLUDED.rejected_outside_vocabulary,"
                + " failures = metadata_model_extraction_stats.failures + EXCLUDED.failures,"
                + " rejected_pool_full = metadata_model_extraction_stats.rejected_pool_full +"
                + " EXCLUDED.rejected_pool_full,"
                + " keywords_assigned = metadata_model_extraction_stats.keywords_assigned +"
                + " EXCLUDED.keywords_assigned,"
                + " last_call_at = now()"
                + " RETURNING calls",
            Long.class,
            libraryId,
            tally.acceptedValues(),
            tally.rejectedBelowThreshold(),
            tally.rejectedOutsideVocabulary(),
            tally.failed() ? 1L : 0L,
            tally.rejectedPoolFull() ? 1L : 0L,
            tally.keywordsAssigned());
    for (ModelExtractionTally.ModelExtractionRejection rejection : tally.rejections()) {
      jdbcTemplate.update(
          "INSERT INTO metadata_model_rejections (id, library_id, document_id, field_key,"
              + " proposed_value, confidence, reason) VALUES (?, ?, ?, ?, ?, ?, ?)",
          UUID.randomUUID(),
          libraryId,
          documentId,
          rejection.fieldKey(),
          truncate(rejection.proposedValue()),
          rejection.confidence(),
          rejection.reason().name());
    }
    if (calls == null || calls % ROTATION_INTERVAL != 0) {
      // Rotating after every document would run a 1000-row subquery per document of a Bestandslauf
      // over the whole bestand; the log may exceed its cap by less than one interval in between.
      // Checked on every call, not only on one that rejected something: a library that stopped
      // producing rejections would otherwise keep its old ones forever.
      return;
    }
    jdbcTemplate.update(
        "DELETE FROM metadata_model_rejections WHERE library_id = ? AND id NOT IN"
            + " (SELECT id FROM metadata_model_rejections WHERE library_id = ?"
            + " ORDER BY created_at DESC, id DESC LIMIT ?)",
        libraryId,
        libraryId,
        REJECTION_CAP);
  }

  /** The Zählwerk of every library in {@code libraryIds}; a library without one reads as zero. */
  public Map<UUID, ModelExtractionStats> statsFor(Collection<UUID> libraryIds) {
    Map<UUID, ModelExtractionStats> result = new HashMap<>();
    if (libraryIds.isEmpty()) {
      return result;
    }
    List<UUID> ids = List.copyOf(libraryIds);
    jdbcTemplate.query(
        "SELECT * FROM metadata_model_extraction_stats WHERE library_id IN ("
            + ids.stream().map(id -> "?").collect(Collectors.joining(", "))
            + ")",
        rs -> {
          UUID libraryId = (UUID) rs.getObject("library_id");
          Timestamp lastCall = rs.getTimestamp("last_call_at");
          result.put(
              libraryId,
              new ModelExtractionStats(
                  libraryId,
                  rs.getLong("calls"),
                  rs.getLong("accepted_values"),
                  rs.getLong("rejected_below_threshold"),
                  rs.getLong("rejected_outside_vocabulary"),
                  rs.getLong("failures"),
                  rs.getLong("rejected_pool_full"),
                  rs.getLong("keywords_assigned"),
                  lastCall == null ? null : lastCall.toInstant()));
        },
        ids.toArray());
    for (UUID libraryId : ids) {
      result.putIfAbsent(libraryId, ModelExtractionStats.empty(libraryId));
    }
    return result;
  }

  /** {@link #statsFor(Collection)} for one library. */
  public ModelExtractionStats statsFor(UUID libraryId) {
    return statsFor(List.of(libraryId)).get(libraryId);
  }

  private static String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= 200 ? value : value.substring(0, 200);
  }
}
