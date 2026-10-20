package io.opaa.indexing.run;

import io.opaa.knowledge.SourceType;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The pending push batches in {@code source_event_batches} (ADR-0053, Entscheidung 10): per library
 * at most one <em>open</em> batch collects the reported keys until it is due, debounced on the
 * database clock from its first key. A drain claims a due batch for a lease, so exactly one
 * instance takes it; keys reported meanwhile open the next batch. A claim that lapsed - its
 * instance stopped - is due again, so no key is lost to a stop or a change of instance.
 *
 * <p>Every change of a library's batches beyond the claim runs under a transaction-scoped advisory
 * lock on the library, so a deferred batch and a new notification never both become the open one.
 */
@Component
class SourceEventBatches {

  /** The first key of the advisory lock on a library's batches. */
  static final int SOURCE_EVENT_BATCH_LOCK_NAMESPACE = 210;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  SourceEventBatches(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** A claimed batch, as it stood when it was claimed. */
  record Claimed(
      UUID id,
      UUID libraryId,
      SourceType sourceType,
      Set<String> keys,
      int dropped,
      boolean overflowed,
      int deferrals) {}

  /**
   * Adds {@code keys} to the open batch of {@code libraryId}, opening it - due {@code debounce}
   * from now - if there is none. More than {@code maxKeys} distinct keys overflow the batch: it
   * forgets its keys and runs the executor's ordinary run.
   */
  void add(
      UUID libraryId,
      SourceType sourceType,
      Set<String> keys,
      int dropped,
      int maxKeys,
      Duration debounce) {
    boolean overflowed = keys.size() > maxKeys;
    String[] kept = overflowed ? new String[0] : keys.toArray(String[]::new);
    transactions.executeWithoutResult(
        status -> {
          lock(libraryId);
          jdbc.update(
              "INSERT INTO source_event_batches (id, library_id, source_type, keys, dropped,"
                  + " overflowed, due_at) VALUES (?, ?, ?, ?, ?, ?,"
                  + " now() + make_interval(secs => ?))"
                  + " ON CONFLICT (library_id) WHERE claimed_until IS NULL DO UPDATE SET"
                  + " dropped = source_event_batches.dropped + EXCLUDED.dropped,"
                  + " overflowed = source_event_batches.overflowed OR EXCLUDED.overflowed"
                  + " OR cardinality(ARRAY(SELECT DISTINCT unnest(source_event_batches.keys"
                  + " || EXCLUDED.keys))) > ?,"
                  + " keys = CASE WHEN source_event_batches.overflowed OR EXCLUDED.overflowed"
                  + " OR cardinality(ARRAY(SELECT DISTINCT unnest(source_event_batches.keys"
                  + " || EXCLUDED.keys))) > ? THEN '{}'::text[]"
                  + " ELSE ARRAY(SELECT DISTINCT unnest(source_event_batches.keys"
                  + " || EXCLUDED.keys)) END",
              UUID.randomUUID(),
              libraryId,
              sourceType.key(),
              kept,
              dropped,
              overflowed,
              seconds(debounce),
              maxKeys,
              maxKeys);
        });
  }

  /**
   * Claims one due batch of {@code sourceTypes} for {@code lease}, the longest due first; a batch
   * another drain holds locked or claimed is skipped. The claim is committed by the statement.
   */
  Optional<Claimed> claimDue(Collection<SourceType> sourceTypes, Duration lease) {
    if (sourceTypes.isEmpty()) {
      return Optional.empty();
    }
    String[] types = sourceTypes.stream().map(SourceType::key).toArray(String[]::new);
    List<Claimed> claimed =
        jdbc.query(
            "UPDATE source_event_batches SET claimed_until = now() + make_interval(secs => ?)"
                + " WHERE id IN (SELECT id FROM source_event_batches WHERE due_at <= now()"
                + " AND (claimed_until IS NULL OR claimed_until < now())"
                + " AND source_type = ANY (?) ORDER BY due_at LIMIT 1 FOR UPDATE SKIP LOCKED)"
                + " RETURNING id, library_id, source_type, keys, dropped, overflowed, deferrals",
            SourceEventBatches::claimed,
            seconds(lease),
            types);
    return claimed.stream().findFirst();
  }

  /** The batch is done with - run started or dropped. */
  void remove(UUID batchId) {
    jdbc.update("DELETE FROM source_event_batches WHERE id = ?", batchId);
  }

  /**
   * Puts {@code batch} back as one more deferral: it joins the library's open batch, keeping the
   * higher deferral count, or becomes the open batch again, due {@code debounce} from now.
   */
  void defer(Claimed batch, int maxKeys, Duration debounce) {
    transactions.executeWithoutResult(
        status -> {
          lock(batch.libraryId());
          int deferrals = batch.deferrals() + 1;
          List<UUID> open =
              jdbc.queryForList(
                  "SELECT id FROM source_event_batches WHERE library_id = ?"
                      + " AND claimed_until IS NULL FOR UPDATE",
                  UUID.class,
                  batch.libraryId());
          if (open.isEmpty()) {
            jdbc.update(
                "UPDATE source_event_batches SET claimed_until = NULL, deferrals = ?,"
                    + " due_at = now() + make_interval(secs => ?) WHERE id = ?",
                deferrals,
                seconds(debounce),
                batch.id());
            return;
          }
          String[] keys = batch.overflowed() ? new String[0] : batch.keys().toArray(String[]::new);
          jdbc.update(
              "UPDATE source_event_batches SET deferrals = GREATEST(deferrals, ?),"
                  + " dropped = dropped + ?,"
                  + " overflowed = overflowed OR ? OR cardinality(ARRAY(SELECT DISTINCT"
                  + " unnest(keys || ?::text[]))) > ?,"
                  + " keys = CASE WHEN overflowed OR ? OR cardinality(ARRAY(SELECT DISTINCT"
                  + " unnest(keys || ?::text[]))) > ? THEN '{}'::text[]"
                  + " ELSE ARRAY(SELECT DISTINCT unnest(keys || ?::text[])) END"
                  + " WHERE id = ?",
              deferrals,
              batch.dropped(),
              batch.overflowed(),
              keys,
              maxKeys,
              batch.overflowed(),
              keys,
              maxKeys,
              keys,
              open.getFirst());
          remove(batch.id());
        });
  }

  private void lock(UUID libraryId) {
    jdbc.queryForObject(
        "SELECT 1 FROM (SELECT pg_advisory_xact_lock("
            + SOURCE_EVENT_BATCH_LOCK_NAMESPACE
            + ", hashtext(CAST(? AS text)))) acquired",
        Integer.class,
        libraryId);
  }

  private static double seconds(Duration duration) {
    return duration.toMillis() / 1000.0;
  }

  private static Claimed claimed(ResultSet rs, int rowNum) throws SQLException {
    Array keys = rs.getArray("keys");
    Set<String> keySet =
        keys == null ? Set.of() : new LinkedHashSet<>(Arrays.asList((String[]) keys.getArray()));
    return new Claimed(
        rs.getObject("id", UUID.class),
        rs.getObject("library_id", UUID.class),
        SourceType.of(rs.getString("source_type")),
        Set.copyOf(keySet),
        rs.getInt("dropped"),
        rs.getBoolean("overflowed"),
        rs.getInt("deferrals"));
  }
}
