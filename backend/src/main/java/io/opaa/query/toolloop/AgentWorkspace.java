package io.opaa.query.toolloop;

import io.opaa.query.AgentRunCancellation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The workspace in front of the agent loop (ADR-0070, Entscheidung 6): one reservation per running
 * run in the database, valid until a deadline taken from the database clock, so the capacity holds
 * across every instance (ADR-0053) and a crashed instance's places free themselves. A reservation
 * is taken under the lock of the fixed row of {@code agent_workspace}: without it two requests
 * under {@code READ COMMITTED} could both count a free place and overbook. Unlike the caps of
 * ADR-0053, Entscheidung 12, the workspace fails closed - without the database there is no run
 * anyway.
 *
 * <p>The reservation also carries the stop mark of a streamed run: the hash of its cancel token and
 * when the person asked to stop. Any instance sets it; the running one reads it before each tool
 * call and model call.
 */
@Component
class AgentWorkspace implements AgentRunCancellation {

  private final JdbcClient jdbc;
  private final TransactionTemplate transaction;
  private final AgentToolLoopProperties properties;

  AgentWorkspace(
      JdbcClient jdbc,
      PlatformTransactionManager transactionManager,
      AgentToolLoopProperties properties) {
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactionManager);
    this.properties = properties;
  }

  /**
   * Takes a place for {@code runId}, or returns {@code false} when every place is taken. Expired
   * reservations are removed on the way.
   */
  boolean tryReserve(UUID runId) {
    Boolean reserved =
        transaction.execute(
            status -> {
              jdbc.sql("SELECT id FROM agent_workspace WHERE id = 1 FOR UPDATE")
                  .query(Integer.class)
                  .single();
              jdbc.sql("DELETE FROM agent_run_reservations WHERE expires_at <= clock_timestamp()")
                  .update();
              int occupied =
                  jdbc.sql("SELECT count(*) FROM agent_run_reservations")
                      .query(Integer.class)
                      .single();
              if (occupied >= properties.workspaceCapacity()) {
                return false;
              }
              jdbc.sql(
                      "INSERT INTO agent_run_reservations (run_id, expires_at)"
                          + " VALUES (:runId, clock_timestamp() + make_interval(secs => :seconds))")
                  .param("runId", runId)
                  .param("seconds", properties.reservationLifetime().toMillis() / 1000.0)
                  .update();
              return true;
            });
    return Boolean.TRUE.equals(reserved);
  }

  /** Gives the place of {@code runId} back; a reservation already expired is no error. */
  void release(UUID runId) {
    jdbc.sql("DELETE FROM agent_run_reservations WHERE run_id = :runId")
        .param("runId", runId)
        .update();
  }

  /**
   * Lets {@code cancelToken} stop the run of {@code runId}, in the hand of {@code userId} who
   * started it; only the token's hash is stored.
   */
  void armCancel(UUID runId, UUID userId, String cancelToken) {
    jdbc.sql(
            "UPDATE agent_run_reservations SET cancel_token_hash = :hash, user_id = :userId"
                + " WHERE run_id = :runId")
        .param("hash", hash(cancelToken))
        .param("userId", userId)
        .param("runId", runId)
        .update();
  }

  @Override
  public void cancel(String cancelToken, UUID callerId) {
    jdbc.sql(
            "UPDATE agent_run_reservations SET cancel_requested_at = clock_timestamp()"
                + " WHERE cancel_token_hash = :hash AND user_id = :callerId"
                + " AND cancel_requested_at IS NULL")
        .param("hash", hash(cancelToken))
        .param("callerId", callerId)
        .update();
  }

  /** Whether the person asked to stop the run of {@code runId}. */
  boolean cancelRequested(UUID runId) {
    return jdbc.sql(
                "SELECT count(*) FROM agent_run_reservations"
                    + " WHERE run_id = :runId AND cancel_requested_at IS NOT NULL")
            .param("runId", runId)
            .query(Integer.class)
            .single()
        > 0;
  }

  static String hash(String cancelToken) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(cancelToken.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing", e);
    }
  }

  /** The places taken now, installation-wide; every instance reports the same figure. */
  double occupied() {
    return jdbc.sql(
            "SELECT count(*) FROM agent_run_reservations WHERE expires_at > clock_timestamp()")
        .query(Integer.class)
        .single();
  }
}
