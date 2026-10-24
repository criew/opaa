package io.opaa.knowledge;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

/**
 * What the most recent call of a maintenance run could not advance, per library, in {@code
 * library_maintenance_skips} (ADR-0053, Entscheidung 12): the status page shows the same number
 * whichever instance ran the call and whichever answers the page, and after a restart. A call that
 * skipped nothing removes the number; a deleted library takes its numbers with it.
 */
@Component
public class LibraryMaintenanceSkips {

  /** The maintenance runs that report what they skipped; stored by name. */
  public enum Maintenance {
    METADATA_BACKFILL,
    CONTEXT_PREFIX_RERUN,
    SCHEMA_CHANGE
  }

  private final JdbcOperations jdbc;

  public LibraryMaintenanceSkips(JdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  /** Records what the most recent call of {@code maintenance} on {@code libraryId} skipped. */
  public void record(Maintenance maintenance, UUID libraryId, long skipped) {
    if (skipped <= 0) {
      clear(maintenance, libraryId);
      return;
    }
    jdbc.update(
        "INSERT INTO library_maintenance_skips (library_id, maintenance, skipped_documents,"
            + " recorded_at) VALUES (?, ?, ?, clock_timestamp()) ON CONFLICT (library_id,"
            + " maintenance) DO UPDATE SET skipped_documents = EXCLUDED.skipped_documents,"
            + " recorded_at = EXCLUDED.recorded_at",
        libraryId,
        maintenance.name(),
        skipped);
  }

  /** Forgets the number of {@code maintenance} on {@code libraryId}. */
  public void clear(Maintenance maintenance, UUID libraryId) {
    jdbc.update(
        "DELETE FROM library_maintenance_skips WHERE library_id = ? AND maintenance = ?",
        libraryId,
        maintenance.name());
  }

  /** The recorded numbers of {@code maintenance} for {@code libraryIds}; absent means zero. */
  public Map<UUID, Long> skippedFor(Maintenance maintenance, Collection<UUID> libraryIds) {
    Map<UUID, Long> byLibrary = new HashMap<>();
    if (libraryIds.isEmpty()) {
      return byLibrary;
    }
    Object[] parameters = new Object[libraryIds.size() + 1];
    parameters[0] = maintenance.name();
    int index = 1;
    for (UUID libraryId : libraryIds) {
      parameters[index++] = libraryId;
    }
    jdbc.query(
        "SELECT library_id, skipped_documents FROM library_maintenance_skips WHERE maintenance ="
            + " ? AND library_id IN ("
            + libraryIds.stream().map(id -> "?").collect(Collectors.joining(", "))
            + ")",
        rs -> {
          byLibrary.put(rs.getObject("library_id", UUID.class), rs.getLong("skipped_documents"));
        },
        parameters);
    return byLibrary;
  }
}
