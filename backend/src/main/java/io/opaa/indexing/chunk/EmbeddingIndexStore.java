package io.opaa.indexing.chunk;

import io.opaa.indexing.chunk.EmbeddingIndexRecord.Run;
import io.opaa.indexing.chunk.EmbeddingIndexRecord.RunKind;
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

  /**
   * The generations of the two columns as the database holds them.
   *
   * @param target {@code null} while the index has no target column
   */
  public record Generations(long current, Long target) {

    /** The column vectors of {@code generation} go to, or {@code null} for a foreign one. */
    public String columnFor(long generation) {
      if (generation == current) {
        return EmbeddingIndexState.EMBEDDING_COLUMN;
      }
      if (target != null && generation == target) {
        return EmbeddingIndexState.TARGET_COLUMN;
      }
      return null;
    }
  }

  /** The namespace of {@link VectorIndexSchema#lockSchemaChanges}, here taken shared. */
  static final int SCHEMA_LOCK_NAMESPACE = 213;

  private static final String RUN_MATCHES =
      " AND run_status = 'RUNNING' AND run_owner_instance_id = ?"
          + " AND coalesce(run_kind, 'REEMBED') = ?"
          + " AND run_generation IS NOT DISTINCT FROM CAST(? AS bigint)";

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

  /** {@link #load}, with the row locked until the caller's transaction ends. */
  public Optional<EmbeddingIndexRecord> loadForUpdate() {
    return jdbcTemplate
        .query("SELECT * FROM embedding_index WHERE id = 1 FOR UPDATE", (rs, row) -> map(rs))
        .stream()
        .findFirst();
  }

  /**
   * The generations of both columns, read under the schema lock in shared mode until the caller's
   * transaction ends. A model change, a discard, a switch and the startup reconciliation take that
   * lock exclusively before they touch the row or the vector table, so the caller writes into the
   * column as it was decided. The lock queues fairly: a writer arriving behind a waiting change
   * waits for it. A target generation without a target model counts as none. Empty without a row.
   * Called inside a transaction only.
   */
  public Optional<Generations> lockGenerations() {
    jdbcTemplate.query(
        "SELECT pg_advisory_xact_lock_shared(?, 0)", rs -> null, SCHEMA_LOCK_NAMESPACE);
    return jdbcTemplate
        .query(
            "SELECT generation, CASE WHEN target_model IS NULL THEN NULL"
                + " ELSE target_generation END AS target_generation"
                + " FROM embedding_index WHERE id = 1",
            (rs, row) ->
                new Generations(
                    rs.getLong("generation"), rs.getObject("target_generation", Long.class)))
        .stream()
        .findFirst();
  }

  /**
   * The target generation the row carries without a target model after a completed run: what a
   * switch by a version that does not know generations leaves behind. Empty otherwise, also after a
   * reconciliation of such a version dropped the target column, which ends no run as completed.
   */
  public Optional<Long> switchedTargetGeneration() {
    return jdbcTemplate
        .query(
            "SELECT target_generation FROM embedding_index WHERE id = 1"
                + " AND target_model IS NULL AND target_generation IS NOT NULL"
                + " AND run_status = 'COMPLETED'",
            (rs, row) -> rs.getLong(1))
        .stream()
        .findFirst();
  }

  /** The generation of the embedding column as the database holds it; empty without a row. */
  public Optional<Long> currentGeneration() {
    return jdbcTemplate
        .query("SELECT generation FROM embedding_index WHERE id = 1", (rs, row) -> rs.getLong(1))
        .stream()
        .findFirst();
  }

  /** The instance that owns the run of the row, empty while none does. */
  public Optional<UUID> runOwner() {
    return jdbcTemplate
        .query(
            "SELECT run_owner_instance_id FROM embedding_index WHERE id = 1",
            (rs, row) -> Optional.ofNullable(rs.getObject(1, UUID.class)))
        .stream()
        .findFirst()
        .flatMap(owner -> owner);
  }

  /**
   * Starts {@code run} owned by {@code owner}, unless the run of the row is no longer the one the
   * caller read: {@code expectedStatus} and {@code expectedOwner} ({@code null} for none). Two
   * instances starting at once therefore start one run.
   *
   * @return whether the run was started
   */
  public boolean startRun(Run run, UUID owner, RunStatus expectedStatus, UUID expectedOwner) {
    return jdbcTemplate.update(
            "UPDATE embedding_index SET run_status = ?, run_started_at = ?, run_started_by = ?,"
                + " run_organization_id = ?, run_finished_at = NULL, run_message = NULL,"
                + " run_kind = ?, run_generation = ?, index_build_started_at = NULL,"
                + " run_owner_instance_id = ?, updated_at = ? WHERE id = 1"
                + " AND run_status IS NOT DISTINCT FROM CAST(? AS text)"
                + " AND run_owner_instance_id IS NOT DISTINCT FROM CAST(? AS uuid)",
            run.status().name(),
            timestamp(run.startedAt()),
            run.startedBy(),
            run.organizationId(),
            run.kindOrReembed().name(),
            run.generation(),
            owner,
            Timestamp.from(Instant.now(clock)),
            expectedStatus == null ? null : expectedStatus.name(),
            expectedOwner)
        == 1;
  }

  /**
   * Makes {@code owner} the owner of the running run, unless it is no longer running or no longer
   * owned by {@code expectedOwner} ({@code null} for none). A build of the target index the dead
   * owner had begun counts no longer; the new owner builds again.
   *
   * @return whether {@code owner} took the run over
   */
  public boolean takeOverRun(UUID owner, UUID expectedOwner) {
    return jdbcTemplate.update(
            "UPDATE embedding_index SET run_owner_instance_id = ?, index_build_started_at = NULL,"
                + " updated_at = ? WHERE id = 1 AND run_status = 'RUNNING'"
                + " AND run_owner_instance_id IS NOT DISTINCT FROM CAST(? AS uuid)",
            owner,
            Timestamp.from(Instant.now(clock)),
            expectedOwner)
        == 1;
  }

  /**
   * Whether the running run is still the one of {@code owner}, {@code kind} and {@code generation}.
   */
  public boolean ownsRunningRun(UUID owner, RunKind kind, Long generation) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM embedding_index WHERE id = 1" + RUN_MATCHES + ")",
            Boolean.class,
            owner,
            kind.name(),
            generation));
  }

  /**
   * {@link #ownsRunningRun}, with the row locked until the caller's transaction ends: an end or a
   * cancellation of another instance waits for it. Called inside a transaction only.
   */
  public boolean lockRunningRunOf(UUID owner, RunKind kind, Long generation) {
    return !jdbcTemplate
        .query(
            "SELECT 1 FROM embedding_index WHERE id = 1" + RUN_MATCHES + " FOR UPDATE",
            (rs, row) -> 1,
            owner,
            kind.name(),
            generation)
        .isEmpty();
  }

  /**
   * Marks that the run of {@code owner} builds the index of the target column; a discard waits
   * while the mark stands on a running run with a live owner.
   *
   * @return whether the run was still this owner's
   */
  public boolean markIndexBuild(UUID owner, RunKind kind, Long generation) {
    return jdbcTemplate.update(
            "UPDATE embedding_index SET index_build_started_at = clock_timestamp(), updated_at = ?"
                + " WHERE id = 1"
                + RUN_MATCHES,
            Timestamp.from(Instant.now(clock)),
            owner,
            kind.name(),
            generation)
        == 1;
  }

  /**
   * Ends the running run with {@code status}, only while it is running and - for an {@code owner}
   * other than {@code null} - owned by {@code owner}. The mark of an index build goes with it.
   *
   * @return whether this call ended the run
   */
  public boolean endRun(UUID owner, RunStatus status, Instant at, String message) {
    return jdbcTemplate.update(
            "UPDATE embedding_index SET run_status = ?, run_finished_at = ?, run_message = ?,"
                + " index_build_started_at = NULL, updated_at = ? WHERE id = 1"
                + " AND run_status = 'RUNNING'"
                + " AND (CAST(? AS uuid) IS NULL OR run_owner_instance_id = ?)",
            status.name(),
            timestamp(at),
            message,
            Timestamp.from(Instant.now(clock)),
            owner,
            owner)
        == 1;
  }

  /** Writes {@code baseUrl} to the column of {@code generation}, after an endpoint change. */
  public void updateBaseUrl(long generation, String baseUrl) {
    jdbcTemplate.update(
        "UPDATE embedding_index SET base_url = CASE WHEN generation = ? THEN ? ELSE base_url END,"
            + " target_base_url = CASE WHEN target_generation = ? THEN ? ELSE target_base_url END,"
            + " updated_at = ? WHERE id = 1",
        generation,
        baseUrl,
        generation,
        baseUrl,
        Timestamp.from(Instant.now(clock)));
  }

  /**
   * Inserts or replaces the row; every column is written except the owner of the run, which only
   * {@link #startRun} and {@link #takeOverRun} set.
   */
  public void save(EmbeddingIndexRecord record) {
    EmbeddingIdentity current = record.current();
    EmbeddingIdentity target = record.target();
    Run run = record.run();
    jdbcTemplate.update(
        "INSERT INTO embedding_index (id, model, base_url, dimensions, generation, target_model,"
            + " target_base_url, target_dimensions, target_generation, run_status, run_started_at,"
            + " run_started_by, run_organization_id, run_finished_at, run_message, run_kind,"
            + " run_generation, index_build_started_at, updated_at)"
            + " VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (id) DO UPDATE SET model = EXCLUDED.model,"
            + " base_url = EXCLUDED.base_url, dimensions = EXCLUDED.dimensions,"
            + " generation = EXCLUDED.generation,"
            + " target_model = EXCLUDED.target_model, target_base_url = EXCLUDED.target_base_url,"
            + " target_dimensions = EXCLUDED.target_dimensions,"
            + " target_generation = EXCLUDED.target_generation, run_status = EXCLUDED.run_status,"
            + " run_started_at = EXCLUDED.run_started_at,"
            + " run_started_by = EXCLUDED.run_started_by,"
            + " run_organization_id = EXCLUDED.run_organization_id,"
            + " run_finished_at = EXCLUDED.run_finished_at, run_message = EXCLUDED.run_message,"
            + " run_kind = EXCLUDED.run_kind, run_generation = EXCLUDED.run_generation,"
            + " index_build_started_at = EXCLUDED.index_build_started_at,"
            + " updated_at = EXCLUDED.updated_at",
        current.model(),
        current.baseUrl(),
        current.dimensions(),
        current.generation(),
        target == null ? null : target.model(),
        target == null ? null : target.baseUrl(),
        target == null ? null : target.dimensions(),
        target == null ? null : target.generation(),
        run.status() == null ? null : run.status().name(),
        timestamp(run.startedAt()),
        run.startedBy(),
        run.organizationId(),
        timestamp(run.finishedAt()),
        run.message(),
        run.kind() == null ? null : run.kind().name(),
        run.generation(),
        timestamp(run.indexBuildStartedAt()),
        Timestamp.from(Instant.now(clock)));
  }

  private static EmbeddingIndexRecord map(ResultSet rs) throws SQLException {
    EmbeddingIdentity current =
        new EmbeddingIdentity(
            rs.getString("model"),
            rs.getString("base_url"),
            rs.getInt("dimensions"),
            rs.getLong("generation"));
    String targetModel = rs.getString("target_model");
    Long targetGeneration = rs.getObject("target_generation", Long.class);
    EmbeddingIdentity target =
        targetModel == null
            ? null
            : new EmbeddingIdentity(
                targetModel,
                rs.getString("target_base_url"),
                rs.getInt("target_dimensions"),
                targetGeneration == null ? 0 : targetGeneration);
    String status = rs.getString("run_status");
    String kind = rs.getString("run_kind");
    Run run =
        new Run(
            status == null ? null : RunStatus.valueOf(status),
            instant(rs.getTimestamp("run_started_at")),
            rs.getObject("run_started_by", UUID.class),
            rs.getObject("run_organization_id", UUID.class),
            instant(rs.getTimestamp("run_finished_at")),
            rs.getString("run_message"),
            kind == null ? null : RunKind.valueOf(kind),
            rs.getObject("run_generation", Long.class),
            instant(rs.getTimestamp("index_build_started_at")));
    return new EmbeddingIndexRecord(current, target, run);
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
