package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The account identity under several providers, against a real Postgres (#1330, ADR-0025
 * Entscheidung 2 and 3): the same person at two providers is two accounts - the e-mail never merges
 * them - and the initial administrator's address grants {@code SYSTEM_ADMIN} only through the
 * trusted provider: the dev issuer in this context's {@code dev} mode, and - the {@code oidc}
 * branch, exercised against a real {@code oidc_providers} row with the same repository - the
 * default provider.
 */
@OpaaIntegrationTest
class UserServiceMultiProviderIntegrationTest {

  private static final String EMAIL = "gleiche.person@behoerde.example";

  @Autowired private UserService userService;
  @Autowired private UserRepository userRepository;
  @Autowired private AuthProperties authProperties;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final String subject = "sub-" + UUID.randomUUID();

  @AfterEach
  void tearDown() {
    jdbcTemplate.update(
        "DELETE FROM spaces WHERE owner_id IN (SELECT id FROM users WHERE subject = ?)", subject);
    jdbcTemplate.update("DELETE FROM users WHERE subject = ?", subject);
  }

  @Test
  void theSamePersonAtTwoProvidersIsTwoAccounts() {
    User beschaeftigte =
        userService.findOrCreateUser(subject, "https://idp.example/realms/a", EMAIL, "Person");
    User partner =
        userService.findOrCreateUser(subject, "https://partner.example/realms/b", EMAIL, "Person");

    assertThat(partner.getId()).isNotEqualTo(beschaeftigte.getId());
    assertThat(
            userRepository
                .findBySubjectAndIssuer(subject, "https://idp.example/realms/a")
                .map(User::getId))
        .contains(beschaeftigte.getId());
    assertThat(
            userRepository
                .findBySubjectAndIssuer(subject, "https://partner.example/realms/b")
                .map(User::getId))
        .contains(partner.getId());
    // a returning sign-in at either provider finds its own account, never the other one
    assertThat(
            userService
                .findOrCreateUser(subject, "https://partner.example/realms/b", EMAIL, "Person")
                .getId())
        .isEqualTo(partner.getId());
  }

  @Test
  void theInitialAdminAddressGrantsSystemAdminOnlyThroughTheTrustedIssuer() {
    String adminEmail = authProperties.initialAdminEmail();
    assertThat(adminEmail).isNotBlank();

    User throughDevIssuer =
        userService.findOrCreateUser(subject, authProperties.dev().issuer(), adminEmail, "Admin");
    User throughOtherIssuer =
        userService.findOrCreateUser(
            subject, "https://partner.example/realms/b", adminEmail, "Admin");

    assertThat(throughDevIssuer.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
    assertThat(throughOtherIssuer.getSystemRole()).isEqualTo(SystemRole.USER);
  }

  @Test
  void inTheOidcModeTheDefaultProviderRowIsTheTrustedOne() {
    String adminEmail = authProperties.initialAdminEmail();
    String defaultIssuer = "https://idp.example/realms/" + UUID.randomUUID() + "/";
    OidcProvider standard =
        new OidcProvider(
            "Beschäftigte",
            defaultIssuer,
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    boolean hadDefault = providerRepository.findByDefaultProviderTrue().isPresent();
    if (!hadDefault) {
      standard.markDefault();
    }
    providerRepository.save(standard);
    try {
      AuthProperties oidc = new AuthProperties("oidc", null, null, adminEmail);
      InitialAdminPolicy policy =
          new InitialAdminPolicy(oidc, new TrustedProvider(oidc, providerRepository));
      String trusted = providerRepository.findByDefaultProviderTrue().orElseThrow().getIssuerUri();

      assertThat(policy.grantsSystemAdmin(adminEmail, trusted)).isTrue();
      assertThat(policy.grantsSystemAdmin(adminEmail, OidcIssuerUrisTrim.of(trusted))).isTrue();
      assertThat(policy.grantsSystemAdmin(adminEmail, "https://partner.example/realms/b"))
          .isFalse();
      assertThat(policy.grantsSystemAdmin("other@behoerde.example", trusted)).isFalse();
    } finally {
      providerRepository.deleteById(standard.getId());
    }
  }

  /** The issuer without its trailing slash, to prove the comparison is normalized. */
  private static final class OidcIssuerUrisTrim {
    static String of(String issuer) {
      return issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    }
  }
}
