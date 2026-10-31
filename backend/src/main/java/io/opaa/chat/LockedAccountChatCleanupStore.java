package io.opaa.chat;

import io.opaa.api.types.ChatCleanupHoldPurpose;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The tables of {@link LockedAccountChatCleanupService}: the moment from which the period may
 * count, the holds and their requests, and the reading of which accounts are due. Only a lock by
 * the administration ({@code local_credentials.locked_reason = 'ADMIN'}) starts a period; an open
 * anlassbezogene Klärung suspends it with the rule of the account deletion ({@code
 * UserRepository#countDeletionBlockers}).
 */
@Repository
class LockedAccountChatCleanupStore {

  /**
   * The conditions of a due account, on {@code lc} and the parameters cutoff, now, now: a hold
   * counts until its {@code held_until}; one without (placed before holds ended by themselves)
   * counts until the nightly run gives it its end.
   */
  private static final String DUE =
      """
      lc.locked_reason = 'ADMIN' AND lc.locked_at IS NOT NULL
        AND s.counting_since IS NOT NULL
        AND GREATEST(lc.locked_at, s.counting_since) < ?
        AND NOT EXISTS (SELECT 1 FROM chat_cleanup_holds h WHERE h.user_id = lc.user_id
            AND (h.held_until IS NULL OR h.held_until > ?))
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
            Timestamp.from(now),
            Timestamp.from(cutoff));
    return count == null ? 0 : count;
  }

  /**
   * Whether {@code userId} is still due, read under a share lock on its credentials row until the
   * end of the transaction: an unlock waits for the deletion, or the deletion sees it. A hold only
   * locks that row without changing it, so a hold committed while this read waited is not in its
   * snapshot; {@link #holdRunsAt} re-reads it afterwards.
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
            Timestamp.from(now),
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

  /**
   * Whether a hold of {@code userId} runs at {@code now}, read in a statement of its own after
   * {@link #lockStillDue}: under read committed it sees every hold committed before that lock was
   * granted.
   */
  boolean holdRunsAt(UUID userId, Instant now) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM chat_cleanup_holds WHERE user_id = ?"
                + " AND (held_until IS NULL OR held_until > ?))",
            Boolean.class,
            userId,
            Timestamp.from(now)));
  }

  /** Takes the credentials row of {@code userId} for the change of its hold. */
  void lockAccount(UUID userId) {
    jdbc.queryForList(
        "SELECT 1 FROM local_credentials WHERE user_id = ? FOR NO KEY UPDATE",
        Integer.class,
        userId);
  }

  /** Whether a hold of {@code userId} runs now. */
  boolean isHeld(UUID userId) {
    return findHold(userId).filter(Hold::running).isPresent();
  }

  /** The hold of {@code userId}, running or run out but not yet ended by the nightly run. */
  Optional<Hold> findHold(UUID userId) {
    return jdbc
        .query(
            "SELECT purpose, reason, held_at, held_until,"
                + " (held_until IS NULL OR held_until > clock_timestamp()) AS running"
                + " FROM chat_cleanup_holds WHERE user_id = ?",
            (rs, i) ->
                new Hold(
                    purposeOf(rs.getString("purpose")),
                    rs.getString("reason"),
                    rs.getTimestamp("held_at").toInstant(),
                    instantOf(rs.getTimestamp("held_until")),
                    rs.getBoolean("running")),
            userId)
        .stream()
        .findFirst();
  }

  /**
   * Places the hold of {@code userId} until now plus {@code maxDays}, or extends a hold already
   * there: purpose, reason and end are replaced, the start is kept. Returns the end.
   */
  Instant placeHold(
      UUID userId,
      UUID organizationId,
      ChatCleanupHoldPurpose purpose,
      String reason,
      int maxDays) {
    return jdbc.queryForObject(
        "INSERT INTO chat_cleanup_holds (user_id, organization_id, held_at, purpose, reason,"
            + " held_until) VALUES (?, ?, clock_timestamp(), ?, ?,"
            + " clock_timestamp() + make_interval(days => ?))"
            + " ON CONFLICT (user_id) DO UPDATE SET purpose = excluded.purpose,"
            + " reason = excluded.reason, held_until = excluded.held_until"
            + " RETURNING held_until",
        (rs, i) -> rs.getTimestamp(1).toInstant(),
        userId,
        organizationId,
        purpose.name(),
        reason,
        maxDays);
  }

  /** Lifts the hold of {@code userId}; whether there was one. */
  boolean resume(UUID userId) {
    return jdbc.update("DELETE FROM chat_cleanup_holds WHERE user_id = ?", userId) > 0;
  }

  /**
   * Gives every hold without an end, placed before holds ended by themselves, the end now plus
   * {@code maxDays}; how many.
   */
  int bound(int maxDays) {
    return jdbc.update(
        "UPDATE chat_cleanup_holds SET held_until = clock_timestamp() + make_interval(days => ?)"
            + " WHERE held_until IS NULL",
        maxDays);
  }

  /** The holds whose end lies at or before {@code now}, at most {@code limit}. */
  List<ExpiredHold> findExpired(Instant now, int limit) {
    return jdbc.query(
        "SELECT user_id, organization_id, purpose, held_until FROM chat_cleanup_holds"
            + " WHERE held_until <= ? ORDER BY held_until, user_id LIMIT ?",
        (rs, i) ->
            new ExpiredHold(
                rs.getObject("user_id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                purposeOf(rs.getString("purpose")),
                rs.getTimestamp("held_until").toInstant()),
        Timestamp.from(now),
        limit);
  }

  /** Ends the hold of {@code userId} if it still ran out at {@code now}; whether it did. */
  boolean endExpired(UUID userId, Instant now) {
    return jdbc.update(
            "DELETE FROM chat_cleanup_holds WHERE user_id = ? AND held_until <= ?",
            userId,
            Timestamp.from(now))
        > 0;
  }

  /** The pending request of {@code userId}. */
  Optional<HoldRequest> findRequest(UUID userId) {
    return jdbc
        .query(
            "SELECT user_id, organization_id, purpose, reason, requested_by_user_id, requested_at"
                + " FROM chat_cleanup_hold_requests WHERE user_id = ?",
            (rs, i) -> requestOf(rs),
            userId)
        .stream()
        .findFirst();
  }

  /** Files a request for {@code userId}; false when one is already pending. */
  boolean insertRequest(
      UUID userId,
      UUID organizationId,
      ChatCleanupHoldPurpose purpose,
      String reason,
      UUID requestedBy) {
    return jdbc.update(
            "INSERT INTO chat_cleanup_hold_requests (user_id, organization_id, purpose, reason,"
                + " requested_by_user_id, requested_at) VALUES (?, ?, ?, ?, ?, clock_timestamp())"
                + " ON CONFLICT (user_id) DO NOTHING",
            userId,
            organizationId,
            purpose.name(),
            reason,
            requestedBy)
        > 0;
  }

  /** Removes the pending request of {@code userId}; whether there was one. */
  boolean deleteRequest(UUID userId) {
    return jdbc.update("DELETE FROM chat_cleanup_hold_requests WHERE user_id = ?", userId) > 0;
  }

  /** Requests filed before {@code before}, at most {@code limit}. */
  List<HoldRequest> findStaleRequests(Instant before, int limit) {
    return jdbc.query(
        "SELECT user_id, organization_id, purpose, reason, requested_by_user_id, requested_at"
            + " FROM chat_cleanup_hold_requests WHERE requested_at < ?"
            + " ORDER BY requested_at, user_id LIMIT ?",
        (rs, i) -> requestOf(rs),
        Timestamp.from(before),
        limit);
  }

  /** Removes the request of {@code userId} if it was still filed before {@code before}. */
  boolean deleteStaleRequest(UUID userId, Instant before) {
    return jdbc.update(
            "DELETE FROM chat_cleanup_hold_requests WHERE user_id = ? AND requested_at < ?",
            userId,
            Timestamp.from(before))
        > 0;
  }

  private static HoldRequest requestOf(ResultSet rs) throws SQLException {
    return new HoldRequest(
        rs.getObject("user_id", UUID.class),
        rs.getObject("organization_id", UUID.class),
        ChatCleanupHoldPurpose.valueOf(rs.getString("purpose")),
        rs.getString("reason"),
        rs.getObject("requested_by_user_id", UUID.class),
        rs.getTimestamp("requested_at").toInstant());
  }

  private static ChatCleanupHoldPurpose purposeOf(String value) {
    return value == null ? null : ChatCleanupHoldPurpose.valueOf(value);
  }

  private static Instant instantOf(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  /**
   * A hold; {@code purpose}, {@code reason} and {@code heldUntil} are null for one placed before
   * holds were approved in pairs, {@code heldUntil} only until the nightly run bounds it.
   */
  record Hold(
      ChatCleanupHoldPurpose purpose,
      String reason,
      Instant heldSince,
      Instant heldUntil,
      boolean running) {}

  /** A hold whose end ran out; {@code purpose} null for one placed before purposes existed. */
  record ExpiredHold(
      UUID userId, UUID organizationId, ChatCleanupHoldPurpose purpose, Instant heldUntil) {}

  /** A request awaiting the second administrator. */
  record HoldRequest(
      UUID userId,
      UUID organizationId,
      ChatCleanupHoldPurpose purpose,
      String reason,
      UUID requestedBy,
      Instant requestedAt) {}

  /** An account whose period started at {@code periodStart} and ran out. */
  record DueAccount(UUID userId, UUID organizationId, Instant periodStart) {}
}
