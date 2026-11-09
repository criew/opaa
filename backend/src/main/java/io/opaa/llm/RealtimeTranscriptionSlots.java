package io.opaa.llm;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The live sessions of the installation (ADR-0070, ADR-0053). The cap counts across all instances:
 * every open session is a row of {@code transcription_realtime_slots}, opened under a table lock
 * and timed by the database clock. A row of a stopped instance stops counting once it is older than
 * {@link #STALE_AFTER}, longer than any session may last. The one open session per person is kept
 * per instance only and names nobody in the database; two sessions of one person on two instances
 * are accepted, since each still counts against the cap.
 */
@Component
public class RealtimeTranscriptionSlots {

  /**
   * The longest session (recording limit plus the wait for the final text, 145 s) with a margin for
   * the clocks of database and instance; an older row no longer counts.
   */
  static final Duration STALE_AFTER = Duration.ofSeconds(180);

  private static final String FRESH =
      "opened_at >= clock_timestamp() - make_interval(secs => " + STALE_AFTER.toSeconds() + ")";

  /** Why no session could be opened. */
  public enum Refusal {
    /** The person has a session open on this instance already. */
    PERSON_BUSY,
    /** The installation holds as many sessions as configured. */
    INSTALLATION_BUSY
  }

  /** An open session's slot; closing it frees the slot. */
  public interface Slot extends AutoCloseable {
    @Override
    void close();
  }

  /** Either a slot or why there is none. */
  public record Opening(Slot slot, Refusal refusal) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Set<UUID> openPersons = ConcurrentHashMap.newKeySet();

  public RealtimeTranscriptionSlots(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** Opens a slot for {@code person} unless the person or the installation is at its limit. */
  public Opening open(UUID person, int maxSessions) {
    if (!openPersons.add(person)) {
      return new Opening(null, Refusal.PERSON_BUSY);
    }
    UUID id = UUID.randomUUID();
    Boolean taken;
    try {
      taken =
          transactions.execute(
              status -> {
                jdbc.execute("LOCK TABLE transcription_realtime_slots IN SHARE ROW EXCLUSIVE MODE");
                jdbc.update("DELETE FROM transcription_realtime_slots WHERE NOT (" + FRESH + ")");
                Integer open =
                    jdbc.queryForObject(
                        "SELECT count(*) FROM transcription_realtime_slots", Integer.class);
                if (open != null && open >= maxSessions) {
                  return false;
                }
                jdbc.update(
                    "INSERT INTO transcription_realtime_slots (id, opened_at)"
                        + " VALUES (?, clock_timestamp())",
                    id);
                return true;
              });
    } catch (RuntimeException e) {
      openPersons.remove(person);
      throw e;
    }
    if (!Boolean.TRUE.equals(taken)) {
      openPersons.remove(person);
      return new Opening(null, Refusal.INSTALLATION_BUSY);
    }
    AtomicBoolean released = new AtomicBoolean();
    Slot slot =
        () -> {
          if (released.compareAndSet(false, true)) {
            openPersons.remove(person);
            try {
              jdbc.update("DELETE FROM transcription_realtime_slots WHERE id = ?", id);
            } catch (DataAccessException e) {
              // The row stops counting after STALE_AFTER; nothing else depends on it.
            }
          }
        };
    return new Opening(slot, null);
  }

  /** Whether the installation holds fewer than {@code maxSessions} sessions; never locks. */
  public boolean hasFree(int maxSessions) {
    Integer open =
        jdbc.queryForObject(
            "SELECT count(*) FROM transcription_realtime_slots WHERE " + FRESH, Integer.class);
    return open == null || open < maxSessions;
  }
}
