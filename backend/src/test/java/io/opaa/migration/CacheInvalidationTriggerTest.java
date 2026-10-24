package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/**
 * The tables bound to the invalidation bus: a change of a cached table is announced on {@code
 * opaa_invalidation} with the topic of its cache - at commit, never after a rollback, once per
 * transaction - and a change of only the status columns of a row trigger is none (ADR-0053,
 * Entscheidung 5).
 */
class CacheInvalidationTriggerTest extends AbstractBaselineTest {

  private Connection listener;

  @BeforeEach
  void listen() throws Exception {
    listener = connect();
    try (Statement statement = listener.createStatement()) {
      statement.execute("LISTEN opaa_invalidation");
    }
  }

  @AfterEach
  void closeListener() throws SQLException {
    listener.close();
  }

  @Test
  void everyCachedTableAnnouncesItsTopic() throws Exception {
    UUID user = insertUser();
    UUID group = insertInternalGroup();
    UUID provider = insertProvider();
    drain();

    execute(
        "INSERT INTO group_memberships (id, user_id, group_id, organization_id) VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + user
            + "', '"
            + group
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "')");
    assertThat(received()).containsExactly("rights");
    execute(
        "UPDATE groups SET deactivated = true, deactivated_at = now() WHERE id = '" + group + "'");
    assertThat(received()).containsExactly("rights");
    execute("DELETE FROM asset_grants WHERE organization_id = '" + SEEDED_ORGANIZATION_ID + "'");
    assertThat(received()).containsExactly("rights");
    execute("UPDATE oidc_providers SET roles_claim = 'roles' WHERE id = '" + provider + "'");
    assertThat(received()).containsExactly("oidc-providers");
    execute("UPDATE llm_models SET max_tokens = max_tokens");
    assertThat(received()).containsExactly("chat-model");
    execute(
        "INSERT INTO outbound_proxy_settings (id, no_proxy, updated_at) VALUES (1, '', now())"
            + " ON CONFLICT (id) DO UPDATE SET proxy_host = 'proxy.example', proxy_port = 3128");
    assertThat(received()).containsExactly("outbound-proxy");
    execute("UPDATE mail_settings SET host = 'smtp.example'");
    assertThat(received()).containsExactly("mail-settings");
    execute("INSERT INTO embedding_index (id, dimensions, updated_at) VALUES (1, 1536, now())");
    assertThat(received()).containsExactly("embedding-index");
  }

  @Test
  void theSendStatusOfTheMailSettingsAndTheProgressOfARunAnnounceNothing() throws Exception {
    execute("INSERT INTO embedding_index (id, dimensions, updated_at) VALUES (1, 1536, now())");
    drain();

    execute(
        "UPDATE mail_settings SET last_success_at = now(), last_failure_at = now(),"
            + " last_failure_reason = 'x', updated_at = now()");
    execute(
        "UPDATE embedding_index SET run_status = 'RUNNING', run_started_at = now(),"
            + " run_message = 'x', updated_at = now()");
    assertThat(received()).isEmpty();

    execute("UPDATE embedding_index SET target_model = 'other', target_dimensions = 768");
    assertThat(received()).containsExactly("embedding-index");
  }

  @Test
  void anAnnouncementArrivesWithTheCommitOnceAndNeverAfterARollback() throws Exception {
    UUID group = insertInternalGroup();
    drain();
    connection.setAutoCommit(false);

    execute("UPDATE groups SET name = 'Erste' WHERE id = '" + group + "'");
    execute("UPDATE groups SET name = 'Zweite' WHERE id = '" + group + "'");
    execute("DELETE FROM asset_grants WHERE false");
    assertThat(received()).as("nothing before the commit").isEmpty();
    connection.commit();
    assertThat(received()).as("folded into one per transaction").containsExactly("rights");

    execute("UPDATE groups SET name = 'Dritte' WHERE id = '" + group + "'");
    connection.rollback();
    assertThat(received()).as("nothing after a rollback").isEmpty();
  }

  /**
   * A new state of a probed model role is announced; a probe that only renews the time of the
   * observation is not (ADR-0053, Entscheidung 12).
   */
  @Test
  void aNewModelRoleStateIsAnnouncedAndARenewedObservationIsNot() throws Exception {
    execute(
        "INSERT INTO model_role_observations (role, base_url, model, reachable, timed_out,"
            + " observed_at) VALUES ('RERANK', 'http://rerank', 'm', true, false, now())");
    assertThat(received()).containsExactly("model-role-observations");

    execute("UPDATE model_role_observations SET observed_at = now() + interval '1 minute'");
    assertThat(received()).isEmpty();

    execute("UPDATE model_role_observations SET reachable = false, diagnostic = 'refused'");
    assertThat(received()).containsExactly("model-role-observations");
  }

  /** What arrived on the listening connection within a short wait. */
  private List<String> received() throws SQLException {
    List<String> topics = new ArrayList<>();
    PGNotification[] notifications = listener.unwrap(PGConnection.class).getNotifications(500);
    if (notifications != null) {
      for (PGNotification notification : notifications) {
        topics.add(notification.getParameter());
      }
    }
    return topics;
  }

  private void drain() throws SQLException {
    received();
  }
}
