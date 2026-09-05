package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The last-administrator protection against a real Postgres (#1331, ADR-0025 Entscheidung 4): the
 * conditional {@code UPDATE} behind {@link TokenRoleSynchronizer} withdraws {@code SYSTEM_ADMIN}
 * only while another administrator of the organization remains, a second withdrawal is refused and
 * audited, and a withdrawal a concurrent request already wrote is read back rather than misreported
 * as refused.
 */
@OpaaIntegrationTest
class TokenRoleSynchronizerIntegrationTest {

  @Autowired private TokenRoleSynchronizer synchronizer;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private User first;
  private User second;
  private final OidcProvider provider =
      new OidcProvider(
          "Beschäftigte",
          "https://idp.example/realms/roles-" + UUID.randomUUID(),
          "opaa-frontend",
          null,
          new OidcClaimMapping(
              null, null, "realm_access.roles", "opaa-admin", "opaa-auditor", null));

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Rollen")).getId();
    first = admin("erste");
    second = admin("zweite");
  }

  private User admin(String subject) {
    User user =
        new User(
            subject + "-" + UUID.randomUUID(),
            provider.getIssuerUri(),
            subject + "@x.example",
            subject);
    user.setOrganizationId(organizationId);
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    return userRepository.save(user);
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    userRepository.deleteAll(List.of(first, second));
    organizationRepository.deleteById(organizationId);
  }

  private SystemRole storedRole(User user) {
    return userRepository.findById(user.getId()).orElseThrow().getSystemRole();
  }

  private List<Map<String, Object>> auditRows() {
    return jdbcTemplate.queryForList(
        "SELECT event_type, outcome FROM audit_log WHERE organization_id = ? ORDER BY recorded_at,"
            + " event_id",
        organizationId);
  }

  @Test
  void theSecondToLastWithdrawalIsWrittenAndTheLastOneIsRefusedAndAudited() {
    User firstAfter = synchronizer.apply(first, provider, List.of("opaa-auditor"));
    assertThat(firstAfter.getSystemRole()).isEqualTo(SystemRole.AUDITOR);
    assertThat(storedRole(first)).isEqualTo(SystemRole.AUDITOR);

    User secondAfter = synchronizer.apply(second, provider, List.of());
    assertThat(secondAfter.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
    assertThat(storedRole(second)).isEqualTo(SystemRole.SYSTEM_ADMIN);

    assertThat(auditRows())
        .extracting(row -> row.get("event_type") + "/" + row.get("outcome"))
        .containsExactly(
            "SYSTEM_ADMIN_ROLE_REVOKED/SUCCESS",
            "AUDITOR_ROLE_GRANTED/SUCCESS",
            "SYSTEM_ADMIN_ROLE_REVOCATION_REFUSED/DENIED");
  }

  @Test
  void anAdministratorOfAnotherOrganizationDoesNotCountAsRemaining() {
    UUID otherOrganization =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Andere")).getId();
    User elsewhere = new User("fremd-" + UUID.randomUUID(), provider.getIssuerUri(), null, null);
    elsewhere.setOrganizationId(otherOrganization);
    elsewhere.setSystemRole(SystemRole.SYSTEM_ADMIN);
    elsewhere = userRepository.save(elsewhere);
    try {
      synchronizer.apply(first, provider, List.of());
      User result = synchronizer.apply(second, provider, List.of());

      assertThat(result.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
      assertThat(storedRole(second)).isEqualTo(SystemRole.SYSTEM_ADMIN);
    } finally {
      userRepository.delete(elsewhere);
      organizationRepository.deleteById(otherOrganization);
    }
  }

  /**
   * The zero-row result of the conditional update has two causes; a role a concurrent request
   * already moved is the other one and must be read back, not reported as a refused withdrawal.
   */
  @Test
  void aWithdrawalAConcurrentRequestAlreadyWroteIsReadBackNotRefused() {
    // the in-memory user still says SYSTEM_ADMIN, the row already says USER
    jdbcTemplate.update("UPDATE users SET system_role = 'USER' WHERE id = ?", first.getId());

    User result = synchronizer.apply(first, provider, List.of());

    assertThat(result.getSystemRole()).isEqualTo(SystemRole.USER);
    assertThat(auditRows()).isEmpty();
  }

  @Test
  void aGrantIsWrittenAndAuditedAgainstTheRealRow() {
    User regular = new User("neu-" + UUID.randomUUID(), provider.getIssuerUri(), null, "Neu");
    regular.setOrganizationId(organizationId);
    regular = userRepository.save(regular);
    try {
      User result = synchronizer.apply(regular, provider, List.of("opaa-admin"));

      assertThat(result.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
      assertThat(storedRole(regular)).isEqualTo(SystemRole.SYSTEM_ADMIN);
      assertThat(auditRows())
          .extracting(row -> row.get("event_type"))
          .containsExactly("SYSTEM_ADMIN_ROLE_GRANTED");
    } finally {
      userRepository.delete(regular);
    }
  }
}
