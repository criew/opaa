package io.opaa.directory.sync.connector;

import io.opaa.auth.OidcAddressPolicy;
import io.opaa.auth.OidcProviderRepository;
import io.opaa.directory.sync.keycloak.KeycloakDirectoryConnector;
import io.opaa.directory.sync.keycloak.KeycloakDirectoryProperties;
import io.opaa.security.AddressCheckingHttpClient;
import io.opaa.security.CredentialsEncryptor;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the directory connectors (#1817). The {@link HttpClient} is one shared instance that never
 * follows a redirect - every address it is given has passed {@link OidcAddressPolicy}, a redirect
 * target would not have - and resolves every connection through that policy, so the address checked
 * is the address connected to.
 */
@Configuration
@EnableConfigurationProperties(KeycloakDirectoryProperties.class)
public class DirectoryConnectorConfiguration {

  @Bean
  HttpClient directoryHttpClient(
      KeycloakDirectoryProperties properties, OidcAddressPolicy addressPolicy) {
    return AddressCheckingHttpClient.newBuilder(addressPolicy)
        .connectTimeout(properties.connectTimeout())
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
