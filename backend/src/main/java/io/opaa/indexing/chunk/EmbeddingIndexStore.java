package io.opaa.indexing.chunk;

import io.opaa.indexing.chunk.EmbeddingIndexRecord.Run;
import io.opaa.indexing.chunk.EmbeddingIndexRecord.RunStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads and writes the single {@code embedding_index} row. */
@Component
public class EmbeddingIndexStore {

  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  public EmbeddingIndexStore(JdbcTemplate jdbcTemplate, Clock clock) {
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  public Optional<EmbeddingIndexRecord> load() {
    return jdbcTemplate
        .query("SELECT * FROM embedding_index WHERE id = 1", (rs, row) -> map(rs))
        .stream()
        .findFirst();
  }

  /** Inserts or replaces the row; every column is written. */
  public void save(EmbeddingIndexRecord record) {
    EmbeddingIdentity current = record.current();
    EmbeddingIdentity target = record.target();
    Run run = record.run();
    jdbcTemplate.update(
        "INSERT INTO embedding_index (id, model, base_url, dimensions, target_model,"
            + " target_base_url, target_dimensions, run_status, run_started_at, run_started_by,"
            + " run_organization_id, run_finished_at, run_message, updated_at)"
            + " VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (id) DO UPDATE SET model = EXCLUDED.model,"
            + " base_url = EXCLUDED.base_url, dimensions = EXCLUDED.dimensions,"
            + " target_model = EXCLUDED.target_model, target_base_url = EXCLUDED.target_base_url,"
            + " target_dimensions = EXCLUDED.target_dimensions, run_status = EXCLUDED.run_status,"
            + " run_started_at = EXCLUDED.run_started_at,"
            + " run_started_by = EXCLUDED.run_started_by,"
            + " run_organization_id = EXCLUDED.run_organization_id,"
            + " run_finished_at = EXCLUDED.run_finished_at, run_message = EXCLUDED.run_message,"
            + " updated_at = EXCLUDED.updated_at",
        current.model(),
        current.baseUrl(),
        current.dimensions(),
        target == null ? null : target.model(),
        target == null ? null : target.baseUrl(),
        target == null ? null : target.dimensions(),
        run.status() == null ? null : run.status().name(),
        timestamp(run.startedAt()),
        run.startedBy(),
        run.organizationId(),
        timestamp(run.finishedAt()),
        run.message(),
        Timestamp.from(Instant.now(clock)));
  }

  private static EmbeddingIndexRecord map(ResultSet rs) throws SQLException {
    EmbeddingIdentity current =
        new EmbeddingIdentity(
            rs.getString("model"), rs.getString("base_url"), rs.getInt("dimensions"));
    String targetModel = rs.getString("target_model");
    EmbeddingIdentity target =
        targetModel == null
            ? null
            : new EmbeddingIdentity(
                targetModel, rs.getString("target_base_url"), rs.getInt("target_dimensions"));
    String status = rs.getString("run_status");
    Run run =
        new Run(
            status == null ? null : RunStatus.valueOf(status),
            instant(rs.getTimestamp("run_started_at")),
            rs.getObject("run_started_by", UUID.class),
            rs.getObject("run_organization_id", UUID.class),
            instant(rs.getTimestamp("run_finished_at")),
            rs.getString("run_message"));
    return new EmbeddingIndexRecord(current, target, run);
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
