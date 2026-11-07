package io.opaa.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The per-account marker "the hint of the quick tour is done" ({@code users.quick_tour_hint_done}):
 * a plain yes/no value, without point in time, counter or progress. {@link User} does not map the
 * column, so no entity write - sign-in, provisioning, directory sync, handover - ever resets it;
 * only the erasure of the personal reference puts it back to {@code false}. Setting it changes no
 * other column and writes no audit entry, no metric and no log line about the person.
 */
@Service
public class QuickTourHint {

  private final JdbcTemplate jdbc;

  public QuickTourHint(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Whether {@code accountId} has dealt with the hint; {@code false} for an unknown account. */
  public boolean isDone(UUID accountId) {
    List<Boolean> done =
        jdbc.queryForList(
            "SELECT quick_tour_hint_done FROM users WHERE id = ?", Boolean.class, accountId);
    return !done.isEmpty() && Boolean.TRUE.equals(done.getFirst());
  }

  /** Marks the hint done for {@code accountId}; idempotent, a second call writes nothing. */
  public void markDone(UUID accountId) {
    jdbc.update(
        "UPDATE users SET quick_tour_hint_done = true WHERE id = ? AND NOT quick_tour_hint_done",
        accountId);
  }
}
