package io.opaa.auth.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.local.LocalCredentials;
import io.opaa.auth.local.LocalCredentialsRepository;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The account list through the real endpoint: a local account and a provider account of the
 * caller's organization appear with their kind, an account of another organization never does, a
 * regular user is refused. Every assertion is filtered by this class's own marker, never made
 * against the unfiltered table (AGENTS.md, "Eine Datenbank für die ganze Suite").
 */
@OpaaIntegrationTest
class AccountAdminIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private LocalCredentialsRepository credentialsRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final String marker = "acct-" + UUID.randomUUID().toString().substring(0, 8);
  private final List<UUID> createdUserIds = new ArrayList<>();
  private UUID otherOrganizationId;
  private UUID createdProviderId;

  @BeforeEach
  void cleanBefore() {
    cleanUp();
  }

  @AfterEach
  void cleanAfter() {
    cleanUp();
  }

  private void cleanUp() {
    for (UUID id : createdUserIds) {
      credentialsRepository.deleteById(id);
      userRepository.deleteById(id);
    }
    createdUserIds.clear();
    if (createdProviderId != null) {
      providerRepository.deleteById(createdProviderId);
      createdProviderId = null;
    }
    if (otherOrganizationId != null) {
      userRepository.deleteAll(userRepository.findByOrganizationId(otherOrganizationId));
      jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", otherOrganizationId);
      organizationRepository.deleteById(otherOrganizationId);
      otherOrganizationId = null;
    }
  }

  @Test
  void listsTheLocalAndTheProviderAccountOfTheOrganizationButNotAnotherOrganizations()
      throws Exception {
    // provisions dev-admin as SYSTEM_ADMIN in Organization.DEFAULT_ID on this first request
    mockMvc
        .perform(get("/api/v1/admin/accounts").with(dev("dev-admin")))
        .andExpect(status().isOk());
    Instant now = Instant.now();
    User local = User.localAccount(marker + "-erika@stadt.example", marker + " Erika");
    local.setOrganizationId(Organization.DEFAULT_ID);
    UUID localId = userRepository.save(local).getId();
    createdUserIds.add(localId);
    LocalCredentials row = new LocalCredentials(localId, "Vertretung Meldewesen", now);
    row.setPasswordHash("not-a-real-hash", now);
    row.markEmailVerified(now);
    credentialsRepository.save(row);
    User provider =
        new User(
            UUID.randomUUID().toString(),
            "test-issuer",
            marker + "-maria@stadt.example",
            marker + " Maria");
    provider.setOrganizationId(Organization.DEFAULT_ID);
    createdUserIds.add(userRepository.save(provider).getId());
    otherOrganizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Other Org")).getId();
    User elsewhere =
        new User(
            UUID.randomUUID().toString(),
            "test-issuer",
            marker + "-other@example.com",
            marker + " Other");
    elsewhere.setOrganizationId(otherOrganizationId);
    userRepository.save(elsewhere);

    mockMvc
        .perform(get("/api/v1/admin/accounts").with(dev("dev-admin")).param("query", marker))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.items[0].displayName").value(marker + " Erika"))
        .andExpect(jsonPath("$.items[0].providerType").value("LOCAL"))
        .andExpect(jsonPath("$.items[0].issuer").value("urn:opaa:local"))
        .andExpect(jsonPath("$.items[0].local.status").value("ACTIVE"))
        .andExpect(jsonPath("$.items[0].local.createdReason").value("Vertretung Meldewesen"))
        .andExpect(jsonPath("$.items[1].displayName").value(marker + " Maria"))
        .andExpect(jsonPath("$.items[1].providerType").value("OIDC"))
        .andExpect(jsonPath("$.items[1].issuer").value("test-issuer"))
        .andExpect(jsonPath("$.items[1].provider").doesNotExist())
        .andExpect(jsonPath("$.items[1].local").doesNotExist());

    mockMvc
        .perform(
            get("/api/v1/admin/accounts")
                .with(dev("dev-admin"))
                .param("query", marker)
                .param("providerType", "LOCAL"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].providerType").value("LOCAL"));
  }

  /**
   * Die einzige nichttriviale Verknüpfung des Endpunkts: Ein Konto findet seine Anbieterzeile über
   * den <em>normalisierten</em> Issuer, und {@code roleManagedByProvider} fällt aus deren
   * Claim-Zuordnung. Der Dienst-Test prüft das gegen ein Mock-Repository - hier steht eine echte
   * Zeile in der Datenbank, mit dem abschließenden Schrägstrich, den die Normalisierung wegnimmt.
   */
  @Test
  void resolvesTheProviderRowOfAnAccountAndReadsItsRoleClaimFromIt() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/accounts").with(dev("dev-admin")))
        .andExpect(status().isOk());
    OidcProvider provider =
        new OidcProvider(
            marker + " Partnerportal",
            "https://idp." + marker + ".example/realms/partner/",
            "opaa-partner",
            null,
            new OidcClaimMapping("email", "name", "realm_access.roles", "opaa-admin", null, null));
    provider.enable();
    createdProviderId = providerRepository.save(provider).getId();
    User account =
        new User(
            UUID.randomUUID().toString(),
            // ohne den abschließenden Schrägstrich der gespeicherten Zeile
            "https://idp." + marker + ".example/realms/partner",
            marker + "-p.admin@partner.example",
            marker + " P. Admin");
    account.setOrganizationId(Organization.DEFAULT_ID);
    createdUserIds.add(userRepository.save(account).getId());

    mockMvc
        .perform(get("/api/v1/admin/accounts").with(dev("dev-admin")).param("query", marker))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].provider.displayName").value(marker + " Partnerportal"))
        .andExpect(jsonPath("$.items[0].provider.enabled").value(true))
        .andExpect(jsonPath("$.items[0].roleManagedByProvider").value(true))
        .andExpect(jsonPath("$.items[0].local").doesNotExist());

    // Ein deaktivierter Anbieter führt die Rollen nicht mehr - er stellt keine Tokens mehr aus.
    provider.disable();
    providerRepository.save(provider);
    mockMvc
        .perform(get("/api/v1/admin/accounts").with(dev("dev-admin")).param("query", marker))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].provider.enabled").value(false))
        .andExpect(jsonPath("$.items[0].roleManagedByProvider").value(false));
  }

  @Test
  void aRegularUserIsRefused() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/accounts").with(dev("dev-user")))
        .andExpect(status().isForbidden());
  }

  private static RequestPostProcessor dev(String devUser) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, devUser);
      return request;
    };
  }
}
