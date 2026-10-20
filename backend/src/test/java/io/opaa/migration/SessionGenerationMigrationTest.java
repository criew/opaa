package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code identity/2026-10-20-session-generation.yaml}: every local account gets a session
 * generation starting at zero; ending the sessions the way the previous version does - setting only
 * {@code password_invalidated_before} - raises it, and no write lowers it (ADR-0053, Entscheidung
 * 6).
 */
class SessionGenerationMigrationTest extends AbstractBaselineTest {

  private static final String FILE = "db/changelog/identity/2026-10-20-session-generation.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void anExistingAccountStartsAtGenerationZero() throws Exception {
    UUID account = insertCredentials();

    applyChangelog(connection, FILE);

    assertThat(generationOf(account)).isZero();
  }

  @Test
  void aCutoffWrittenWithoutTheGenerationRaisesIt() throws Exception {
    applyChangelog(connection, FILE);
    UUID account = insertCredentials();

    execute(
        "UPDATE local_credentials SET password_invalidated_before = now(), updated_at = now()"
            + " WHERE user_id = '"
            + account
            + "'");
    execute(
        "UPDATE local_credentials SET password_invalidated_before = now() + interval '1 second'"
            + " WHERE user_id = '"
            + account
            + "'");

    assertThat(generationOf(account)).isEqualTo(2);
  }

  @Test
  void aRaiseThatComesWithTheCutoffCountsOnceAndOtherWritesCountNone() throws Exception {
    applyChangelog(connection, FILE);
    UUID account = insertCredentials();

    execute(
        "UPDATE local_credentials SET password_invalidated_before = now(),"
            + " session_generation = session_generation + 1 WHERE user_id = '"
            + account
            + "'");
    execute(
        "UPDATE local_credentials SET failed_login_attempts = 3 WHERE user_id = '" + account + "'");

    assertThat(generationOf(account)).isEqualTo(1);
  }

  @Test
  void noWriteLowersTheGeneration() throws Exception {
    applyChangelog(connection, FILE);
    UUID account = insertCredentials();
    execute(
        "UPDATE local_credentials SET session_generation = 5 WHERE user_id = '" + account + "'");

    execute(
        "UPDATE local_credentials SET session_generation = 4, failed_login_attempts = 1"
            + " WHERE user_id = '"
            + account
            + "'");

    assertThat(generationOf(account)).isEqualTo(5);
  }

  private UUID insertCredentials() throws Exception {
    UUID user = insertUser();
    execute(
        "INSERT INTO local_credentials (user_id, created_reason, created_at, updated_at) VALUES ('"
            + user
            + "', 'Test', now(), now())");
    return user;
  }

  private long generationOf(UUID account) throws Exception {
    return longOf(
        "SELECT session_generation FROM local_credentials WHERE user_id = '" + account + "'");
  }
}
