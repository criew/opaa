package io.opaa.directory.sync.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.opaa.auth.OidcAddressPolicy;
import io.opaa.auth.OidcProviderRepository;
import io.opaa.directory.sync.DirectoryClient;
import io.opaa.directory.sync.DirectoryUnavailableException;
import io.opaa.directory.sync.keycloak.FakeKeycloakServer;
import io.opaa.directory.sync.keycloak.KeycloakDirectoryConnector;
import io.opaa.directory.sync.keycloak.KeycloakDirectoryProperties;
import io.opaa.directory.sync.keycloak.KeycloakRealmAddress;
import io.opaa.security.CredentialsEncryptor;
import io.opaa.security.RebindingHostLookup;
import io.opaa.security.TargetAddressValidator;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one thing the container suite cannot show without leaving the shared test signature (where a
 * {@code @Primary} {@code FakeDirectoryClient} displaces it): that {@link ProviderDirectoryClient}
 * is the {@link DirectoryClient} the production wiring publishes, and that its HTTP client follows
 * no redirect - every address it is handed passed the policy, a redirect target would not have.
 *
 * <p>An {@link ApplicationContextRunner}, not a Spring Boot test: it starts no application, no
 * container and no database, and adds no context signature.
 */
class DirectoryConnectorConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(Collaborators.class, DirectoryConnectorConfiguration.class)
          .withPropertyValues(
              "opaa.directory-sync.keycloak.page-size=100",
              "opaa.directory-sync.keycloak.max-groups=5000",
              "opaa.directory-sync.keycloak.max-members-per-group=20000",
              "opaa.directory-sync.keycloak.max-accounts=50000",
              "opaa.directory-sync.keycloak.connect-timeout=5s",
              "opaa.directory-sync.keycloak.request-timeout=30s");

  @Test
  void theProductiveDirectoryClientIsTheProviderDirectoryClient() {
    runner.run(
        context ->
            assertThat(context)
                .getBean(DirectoryClient.class)
                .isInstanceOf(ProviderDirectoryClient.class));
  }

  @Test
  void theKeycloakConnectorIsPublishedWithAnHttpClientThatFollowsNoRedirect() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(KeycloakDirectoryConnector.class);
          assertThat(context.getBean("directoryHttpClient", HttpClient.class).followRedirects())
              .isEqualTo(HttpClient.Redirect.NEVER);
        });
  }

  /**
   * Regression guard for #1860: the admin API address passes the policy when it is checked ({@code
   * ProviderDirectoryClient}) and must pass it again when the published client connects. {@code
   * localhost} answers a public address to the check and the loopback to the connection.
   */
  @Test
  void theAdminApiOfARealmThatRebindsAfterTheCheckIsNeverContacted() throws Exception {
    RebindingHostLookup lookup = new RebindingHostLookup("localhost");
    OidcAddressPolicy policy =
        new OidcAddressPolicy(new TargetAddressValidator(true, List.of(), lookup));
    DirectoryConnectorConfiguration configuration = new DirectoryConnectorConfiguration();
    KeycloakDirectoryProperties properties =
        new KeycloakDirectoryProperties(
            100, 5000, 20000, 50000, Duration.ofSeconds(5), Duration.ofSeconds(10));
    KeycloakDirectoryConnector connector =
        configuration.keycloakDirectoryConnector(
            configuration.directoryHttpClient(properties, policy), properties, Clock.systemUTC());
    try (FakeKeycloakServer keycloak = new FakeKeycloakServer()) {
      String issuer = keycloak.issuerUri().replace("127.0.0.1", "localhost");
      policy.requireAllowed(issuer, "Issuer-URI");

      assertThatThrownBy(
              () ->
                  connector.fetchSnapshot(
                      KeycloakRealmAddress.of(issuer, null),
                      FakeKeycloakServer.CLIENT_ID,
                      FakeKeycloakServer.CLIENT_SECRET))
          .isInstanceOf(DirectoryUnavailableException.class)
          .hasMessageContaining("gesperrten Adressbereich");
      assertThat(keycloak.requestedPaths()).isEmpty();
      assertThat(keycloak.tokenRequests()).isZero();
    }
    assertThat(lookup.lookups()).isEqualTo(2);
  }

  @Test
  void theSecretConverterIsPublishedSoHibernateResolvesItFromTheBeanContainer() {
    runner.run(
        context -> assertThat(context).hasSingleBean(DirectoryConnectorSecretConverter.class));
  }

  @Configuration(proxyBeanMethods = false)
  static class Collaborators {

    @Bean
    DirectoryConnectorRepository directoryConnectorRepository() {
      return mock(DirectoryConnectorRepository.class);
    }

    @Bean
    OidcProviderRepository oidcProviderRepository() {
      return mock(OidcProviderRepository.class);
    }

    @Bean
    OidcAddressPolicy oidcAddressPolicy() {
      return new OidcAddressPolicy(new TargetAddressValidator(true, List.of("kc.example")));
    }

    @Bean
    CredentialsEncryptor credentialsEncryptor() {
      return mock(CredentialsEncryptor.class);
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }
}
