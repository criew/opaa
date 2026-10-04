package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proxy and TLS switch of a connection profile, applied to an existing installation: a profile
 * already there keeps no proxy and the certificate check on.
 */
class ConnectionProfileTransportMigrationTest extends AbstractBaselineTest {

  private static final String FILE = "db/changelog/connections/2026-10-04-profile-transport.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void anExistingProfileKeepsNoProxyAndChecksCertificates() throws Exception {
    UUID profile = UUID.randomUUID();
    execute(
        "INSERT INTO connection_profiles (id, name, source_type, server_url, auth_method,"
            + " ownership, created_at, updated_at, version) VALUES ('"
            + profile
            + "', 'Zugang Bestand', 'PROFILE_PROBE', 'https://probe.example.org', 'NONE',"
            + " 'LIBRARY', now(), now(), 0)");

    applyChangelog(connection, FILE);

    String row = "id = '" + profile + "'";
    assertThat(countWhere("connection_profiles", row + " AND source_proxy IS NULL")).isEqualTo(1);
    assertThat(booleanOf("SELECT source_insecure_ssl FROM connection_profiles WHERE " + row))
        .isFalse();
    execute(
        "INSERT INTO connection_profiles (id, name, source_type, server_url, auth_method,"
            + " ownership, created_at, updated_at, version) VALUES (gen_random_uuid(),"
            + " 'Zugang neu', 'PROFILE_PROBE', 'https://probe.example.org', 'NONE', 'LIBRARY',"
            + " now(), now(), 0)");
    assertThat(countWhere("connection_profiles", "source_insecure_ssl")).isZero();
  }
}
