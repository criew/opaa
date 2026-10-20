package io.opaa.directory.sync.keycloak;

import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning of the Keycloak directory connector. Deliberately holds no credentials: those live
 * encrypted in {@code directory_connectors}, one row per provider, and never in {@code
 * application.yml} (ADR-0036, Entscheidung 3).
 *
 * @param pageSize the {@code max} of every {@code first}/{@code max} page the admin API is asked
 *     for. Keycloak's own default page size is small enough that a realm of a few hundred groups
 *     would otherwise be read in dozens of round trips.
 * @param maxGroups the ceiling on how many groups one run reads. Reached, the run fails as
 *     unreachable rather than continuing with a truncated group list: a truncated list looks
 *     exactly like a reorganisation that dissolved everything beyond it, and that must never be
 *     applied, the same reasoning as for an empty result.
 * @param maxMembersPerGroup the same ceiling per group's member list, for the same reason.
 * @param maxAccounts the same ceiling for the realm's account list, for the same reason: a
 *     truncated account list looks exactly like a mass departure, and the run would lock everyone
 *     beyond it.
 * @param connectTimeout how long a single connection attempt may take
 * @param requestTimeout how long a single request may take
 */
@PerInstance("paging, ceilings and timeouts of a directory run")
@NoDataImpact("technical bounds, budgets and timeouts")
@ConfigurationProperties(prefix = "opaa.directory-sync.keycloak")
public record KeycloakDirectoryProperties(
    int pageSize,
    int maxGroups,
    int maxMembersPerGroup,
    int maxAccounts,
    @NoDataImpact("a timeout of one request") Duration connectTimeout,
    @NoDataImpact("a timeout of one request") Duration requestTimeout) {

  public KeycloakDirectoryProperties {
    if (pageSize < 1) {
      throw new IllegalArgumentException(
          "opaa.directory-sync.keycloak.page-size must be at least 1, got " + pageSize);
    }
    if (maxGroups < 1) {
      throw new IllegalArgumentException(
          "opaa.directory-sync.keycloak.max-groups must be at least 1, got " + maxGroups);
    }
    if (maxMembersPerGroup < 1) {
      throw new IllegalArgumentException(
          "opaa.directory-sync.keycloak.max-members-per-group must be at least 1, got "
              + maxMembersPerGroup);
    }
    if (maxAccounts < 1) {
      throw new IllegalArgumentException(
          "opaa.directory-sync.keycloak.max-accounts must be at least 1, got " + maxAccounts);
    }
  }
}
