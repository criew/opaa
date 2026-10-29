package io.opaa.chat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The tables of {@link LockedAccountChatCleanupService}: the moment from which the period may
 * count, the holds, and the reading of which accounts are due. Only a lock by the administration
 * ({@code local_credentials.locked_reason = 'ADMIN'}) starts a period; an open anlassbezogene
 * Klärung suspends it with the rule of the account deletion ({@code
 * UserRepository#countDeletionBlockers}).
 */
@Repository
class LockedAccountChatCleanupStore {

  /** The conditions of a due account, on {@code lc} and the parameters cutoff, now. */
  private static final String DUE =
      """
      lc.locked_reason = 'ADMIN' AND lc.locked_at IS NOT NULL
        AND s.counting_since IS NOT NULL
        AND GREATEST(lc.locked_at, s.counting_since) < ?
        AND NOT EXISTS (SELECT 1 FROM chat_cleanup_holds h WHERE h.user_id = lc.user_id)
        AND NOT EXISTS (SELECT 1 FROM audit_incident_scope_grants g
            WHERE (g.subject_user_id = lc.user_id OR g.requested_by_user_id = lc.user_id
                OR g.approved_by_user_id = lc.user_id)
              AND (g.status = 'PENDING' OR g.usable_until >= ?))
      """;

  private final JdbcTemplate jdbc;

  LockedAccountChatCleanupStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The moment from which a period may count; empty while the deletion is switched off. */
  Optional<Instant> countingSince() {
    List<Timestamp> rows =
        jdbc.queryForList(
            "SELECT counting_since FROM chat_locked_account_cleanup_state WHERE id = 1",
            Timestamp.class);
    return rows.isEmpty() || rows.getFirst() == null
        ? Optional.empty()
        : Optional.of(rows.getFirst().toInstant());
  }

  /** Forgets the moment, so that switching on again starts every period anew. */
  void stopCounting() {
    jdbc.update(
        "UPDATE chat_locked_account_cleanup_state SET counting_since = NULL"
            + " WHERE id = 1 AND counting_since IS NOT NULL");
  }

  /** Starts counting from now if it was stopped; whether this call started it. */
  boolean startCountingIfStopped() {
    return jdbc.update(
            "UPDATE chat_locked_account_cleanup_state SET counting_since = clock_timestamp()"
                + " WHERE id = 1 AND counting_since IS NULL")
        > 0;
  }

  /**
   * Accounts due at {@code cutoff} that still author a chat, the longest due first, at most {@code
   * limit}.
   */
  List<DueAccount> findDue(Instant cutoff, Instant now, int limit) {
    return jdbc.query(
        "SELECT lc.user_id, u.organization_id, GREATEST(lc.locked_at, s.counting_since) AS start"
            + " FROM local_credentials lc JOIN users u ON u.id = lc.user_id"
            + " CROSS JOIN chat_locked_account_cleanup_state s WHERE "
            + DUE
            + " AND EXISTS (SELECT 1 FROM chats c WHERE c.author_id = lc.user_id"
            + " AND c.status = 'PRIVATE')"
            + " ORDER BY start, lc.user_id LIMIT ?",
        (rs, i) ->
            new DueAccount(
                rs.getObject("user_id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getTimestamp("start").toInstant()),
        Timestamp.from(cutoff),
        Timestamp.from(now),
        limit);
  }

  /**
   * Accounts not yet due at {@code cutoff} that are due at {@code later}, with or without chats; a
   * hold or an open Klärung at {@code now} leaves an account out, as it does in {@link #findDue}.
   */
  int countDueBetween(Instant cutoff, Instant later, Instant now) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM local_credentials lc"
                + " CROSS JOIN chat_locked_account_cleanup_state s WHERE "
                + DUE
                + " AND GREATEST(lc.locked_at, s.counting_since) >= ?",
            Integer.class,
            Timestamp.from(later),
            Timestamp.from(now),
            Timestamp.from(cutoff));
    return count == null ? 0 : count;
  }

  /**
   * Whether {@code userId} is still due, read under a share lock on its credentials row until the
   * end of the transaction: an unlock or a hold waits for the deletion, or the deletion sees it.
   */
  boolean lockStillDue(UUID userId, Instant cutoff, Instant now) {
    return !jdbc.queryForList(
            "SELECT 1 FROM local_credentials lc"
                + " CROSS JOIN chat_locked_account_cleanup_state s WHERE lc.user_id = ? AND "
                + DUE
                + " FOR SHARE OF lc",
            Integer.class,
            userId,
            Timestamp.from(cutoff),
            Timestamp.from(now))
        .isEmpty();
  }

  /**
   * Up to {@code limit} private chats {@code userId} authored, locked until the end of the
   * transaction. Only {@link io.opaa.api.types.ChatStatus#PRIVATE}: a chat shared in its space
   * stays until its space's cleanup or a deletion by hand.
   */
  List<UUID> lockChatsOf(UUID userId, int limit) {
    return jdbc.queryForList(
        "SELECT id FROM chats WHERE author_id = ? AND status = 'PRIVATE'"
            + " ORDER BY id LIMIT ? FOR UPDATE",
        UUID.class,
        userId,
        limit);
  }

  /** Takes the credentials row of {@code userId} for the change of its hold. */
  void lockAccount(UUID userId) {
    jdbc.queryForList(
        "SELECT 1 FROM local_credentials WHERE user_id = ? FOR NO KEY UPDATE",
        Integer.class,
        userId);
  }

  boolean isHeld(UUID userId) {
    return !jdbc.queryForList(
            "SELECT 1 FROM chat_cleanup_holds WHERE user_id = ?", Integer.class, userId)
        .isEmpty();
  }

  /** Holds {@code userId}; whether this call placed the hold. */
  boolean hold(UUID userId, UUID organizationId) {
    return jdbc.update(
            "INSERT INTO chat_cleanup_holds (user_id, organization_id, held_at)"
                + " VALUES (?, ?, clock_timestamp()) ON CONFLICT (user_id) DO NOTHING",
            userId,
            organizationId)
        > 0;
  }

  /** Lifts the hold of {@code userId}; whether there was one. */
  boolean resume(UUID userId) {
    return jdbc.update("DELETE FROM chat_cleanup_holds WHERE user_id = ?", userId) > 0;
  }

  /** An account whose period started at {@code periodStart} and ran out. */
  record DueAccount(UUID userId, UUID organizationId, Instant periodStart) {}
}
