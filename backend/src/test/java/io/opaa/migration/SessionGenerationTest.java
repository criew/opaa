package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The session generation of a local account: a new account starts at zero, only the application
 * raises it, and no write lowers it (ADR-0053, Entscheidung 6).
 */
class SessionGenerationTest extends AbstractBaselineTest {

  @Test
  void aNewAccountStartsAtGenerationZero() throws Exception {
    UUID account = insertCredentials();

    assertThat(generationOf(account)).isZero();
  }

  @Test
  void noWriteLowersTheGeneration() throws Exception {
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
