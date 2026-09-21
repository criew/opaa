package io.opaa.group.sync.connector;

import io.opaa.auth.oidc.OidcAddressPolicy;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.group.sync.keycloak.KeycloakDirectoryConnector;
import io.opaa.group.sync.keycloak.KeycloakDirectoryProperties;
import io.opaa.security.CredentialsEncryptor;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the directory connectors (#1817). The {@link HttpClient} is one shared instance that never
 * follows a redirect: every address it is given has passed {@link OidcAddressPolicy}, a redirect
 * target would not have.
 */
@Configuration
@EnableConfigurationProperties(KeycloakDirectoryProperties.class)
public class DirectoryConnectorConfiguration {

  @Bean
  HttpClient directoryHttpClient(KeycloakDirectoryProperties properties) {
    return HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @Bean
  KeycloakDirectoryConnector keycloakDirectoryConnector(
      HttpClient directoryHttpClient, KeycloakDirectoryProperties properties, Clock clock) {
    return new KeycloakDirectoryConnector(directoryHttpClient, properties, clock);
  }

  @Bean
  ProviderDirectoryClient providerDirectoryClient(
      DirectoryConnectorRepository connectors,
      OidcProviderRepository providers,
      OidcAddressPolicy addressPolicy,
      KeycloakDirectoryConnector keycloak) {
    return new ProviderDirectoryClient(connectors, providers, addressPolicy, keycloak);
  }

  /**
   * Hibernate resolves a {@code @Convert}-named converter through Spring's bean container, so the
   * encryptor arrives by ordinary constructor injection - the same wiring {@code
   * SourceCredentialsConverter} relies on.
   */
  @Bean
  DirectoryConnectorSecretConverter directoryConnectorSecretConverter(
      CredentialsEncryptor credentialsEncryptor) {
    return new DirectoryConnectorSecretConverter(credentialsEncryptor);
  }
}
