package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A profile's own sign-in, applied to an existing installation: the client secret column becomes
 * {@code text}, a stored ciphertext keeps its value, an encrypted key file beyond the former 3000
 * characters fits, and no profile is marked as rejected.
 */
class ConnectionProfileSignInMigrationTest extends AbstractBaselineTest {

  private static final String FILE = "db/changelog/connections/2026-10-04-profile-sign-in.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void theSecretColumnBecomesTextAndNoProfileIsRejected() throws Exception {
    UUID profile = insertProfile("Zugang Bestand");
    String stored = "enc:v1:" + "A".repeat(2990);
    execute(
        "UPDATE connection_profiles SET client_secret_ciphertext = '"
            + stored
            + "' WHERE id = '"
            + profile
            + "'");
    assertThat(columnType("client_secret_ciphertext")).isEqualTo("character varying");

    applyChangelog(connection, FILE);

    assertThat(columnType("client_secret_ciphertext")).isEqualTo("text");
    assertThat(
            stringOf(
                "SELECT client_secret_ciphertext FROM connection_profiles WHERE id = '"
                    + profile
                    + "'"))
        .isEqualTo(stored);
    assertThat(columnType("sign_in_rejected_at")).isEqualTo("timestamp with time zone");
    assertThat(countWhere("connection_profiles", "sign_in_rejected_at IS NOT NULL")).isZero();
  }

  @Test
  void anEncryptedKeyFileBeyondTheFormerWidthFits() throws Exception {
    applyChangelog(connection, FILE);
    UUID profile = insertProfile("Zugang Drive");
    String wide = "enc:v1:" + "B".repeat(5600);

    execute(
        "UPDATE connection_profiles SET client_secret_ciphertext = '"
            + wide
            + "' WHERE id = '"
            + profile
            + "'");

    assertThat(
            longOf(
                "SELECT length(client_secret_ciphertext) FROM connection_profiles WHERE id = '"
                    + profile
                    + "'"))
        .isEqualTo(wide.length());
  }

  private UUID insertProfile(String name) throws Exception {
    UUID profile = UUID.randomUUID();
    execute(
        "INSERT INTO connection_profiles (id, name, source_type, server_url, auth_method,"
            + " ownership, client_id, created_at, updated_at, version) VALUES ('"
            + profile
            + "', '"
            + name
            + "', 'GOOGLE_DRIVE', 'https://www.googleapis.com', 'SERVICE_ACCOUNT_KEY',"
            + " 'LIBRARY', 'opaa@projekt.iam.gserviceaccount.com', now(), now(), 0)");
    return profile;
  }

  private String columnType(String column) throws Exception {
    return stringOf(
        "SELECT data_type FROM information_schema.columns WHERE table_schema = current_schema()"
            + " AND table_name = 'connection_profiles' AND column_name = '"
            + column
            + "'");
  }
}
