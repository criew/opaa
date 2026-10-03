package io.opaa.test;

import io.opaa.organization.Organization;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The connector release for tests that are not about it: a test connector or a new profile is off
 * by default (ADR-0036, Nachtrag of 03.10.2026), so such a test releases its scope to all accounts
 * of the default organization and withdraws it afterwards. Writes the grant row only, no interval.
 */
public final class ConnectorReleases {

  private ConnectorReleases() {}

  /** Releases {@code scope} ({@code TYPE:<key>} or {@code PROFILE:<id>}) to all accounts. */
  public static void releaseToAllAccounts(JdbcTemplate jdbc, String scope) {
    jdbc.update(
        "INSERT INTO capability_grants (id, organization_id, capability, scope, subject_type,"
            + " created_at) VALUES (?, ?, 'CREATE_CONNECTOR_LIBRARY', ?, 'ALL_ACCOUNTS', now())"
            + " ON CONFLICT DO NOTHING",
        UUID.randomUUID(),
        Organization.DEFAULT_ID,
        scope);
  }

  /** Removes every grant and interval of {@code scope}, also those the API wrote. */
  public static void withdraw(JdbcTemplate jdbc, String scope) {
    jdbc.update("DELETE FROM capability_grants WHERE scope = ?", scope);
    jdbc.update("DELETE FROM capability_grant_history WHERE scope = ?", scope);
  }
}
