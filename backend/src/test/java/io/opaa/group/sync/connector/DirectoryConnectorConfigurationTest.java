package io.opaa.group.sync.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opaa.auth.oidc.OidcAddressPolicy;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.group.sync.DirectoryClient;
import io.opaa.group.sync.keycloak.KeycloakDirectoryConnector;
import io.opaa.security.CredentialsEncryptor;
import io.opaa.security.TargetAddressValidator;
import java.net.http.HttpClient;
import java.time.Clock;
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
