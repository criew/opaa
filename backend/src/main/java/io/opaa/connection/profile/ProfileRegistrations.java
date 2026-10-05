package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.common.NotFoundException;
import io.opaa.indexing.source.ClientAuthentication;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.Endpoint;
import io.opaa.indexing.source.OAuthAuth;
import io.opaa.indexing.source.Revocation;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SignInDetails;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.security.CredentialsEncryptionKeyMissingException;
import io.opaa.security.CredentialsEncryptor;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one way a profile's registration leaves this package: {@link #registrationOf} for the sign-in
 * in {@code connection.oauth} alone, and the mark of a registration the provider rejected. The mark
 * is written in a transaction of its own: the rejection is a fact of the provider, kept whether or
 * not the caller's work commits, and a caller's read-only transaction cannot write it.
 */
@Component
public class ProfileRegistrations {

  private static final Logger log = LoggerFactory.getLogger(ProfileRegistrations.class);

  private final ConnectionProfileRepository profiles;
  private final CredentialsEncryptor encryptor;
  private final TransactionTemplate ownTransaction;
  private final Clock clock;

  /** Looked up per call: the connectors reach the core, and the core this package. */
  private final ObjectProvider<SourceConnectorRegistry> connectors;

  public ProfileRegistrations(
      ConnectionProfileRepository profiles,
      CredentialsEncryptor encryptor,
      PlatformTransactionManager transactions,
      ObjectProvider<SourceConnectorRegistry> connectors,
      Clock clock) {
    this.profiles = profiles;
    this.encryptor = encryptor;
    this.ownTransaction = new TransactionTemplate(transactions);
    this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.connectors = connectors;
    this.clock = clock;
  }

  /**
   * The registration of the profile {@code profileId} with its decrypted secret.
   *
   * @throws NotFoundException for a profile that does not exist
   */
  public ClientRegistration registrationOf(UUID profileId) {
    ConnectionProfile profile =
        profiles
            .findById(profileId)
            .orElseThrow(() -> new NotFoundException("Zugang nicht gefunden"));
    if (profile.isMcpServer()) {
      return mcpServerRegistration(profile);
    }
    SourceConnector connector = connectors.getObject().connector(profile.getSourceType());
    ConnectionAuthMethod method = profile.getAuthMethod();
    SignInDetails signIn =
        connector
            .descriptor()
            .profileDeclaration()
            .signIn(method)
            .map(SignIn::details)
            .orElse(null);
    String subject =
        method == ConnectionAuthMethod.SERVICE_ACCOUNT_KEY
            ? ServiceAccountTokens.subjectOf(
                connector, ConnectorData.fromJson(profile.getConnectorSettings()))
            : null;
    return new ClientRegistration(
        profile.getId(),
        method,
        profile.getClientId(),
        secretOf(profile),
        profile.getTenant(),
        profile.getScopes(),
        signIn,
        subject,
        profile.getSourceProxy(),
        profile.isSignInRejected(),
        profile.getEndpoints(),
        profile.getVersion());
  }

  /**
   * The registration of an MCP server: OAuth with the endpoints discovered when it was saved,
   * revoked by RFC 7009 where its authorization server names an endpoint, every request naming the
   * server as resource.
   */
  private ClientRegistration mcpServerRegistration(ConnectionProfile profile) {
    ProfileEndpoints endpoints = profile.getEndpoints();
    OAuthAuth signIn =
        new OAuthAuth(
            new Endpoint.FromProfile(),
            new Endpoint.FromProfile(),
            endpoints.revocation() == null
                ? new Revocation.None()
                : new Revocation.Rfc7009(new Endpoint.FromProfile()),
            null,
            Map.of(),
            ClientAuthentication.CLIENT_SECRET_BASIC);
    return new ClientRegistration(
        profile.getId(),
        ConnectionAuthMethod.OAUTH,
        profile.getClientId(),
        secretOf(profile),
        null,
        profile.getScopes(),
        signIn,
        null,
        profile.getSourceProxy(),
        profile.isSignInRejected(),
        endpoints,
        profile.getVersion(),
        McpServerResource.of(profile));
  }

  /** The provider rejected the registration of {@code profileId}: marked until lifted. */
  public void rejected(UUID profileId) {
    Instant now = clock.instant();
    ownTransaction.executeWithoutResult(status -> profiles.markSignInRejected(profileId, now));
    log.warn("The provider rejected the sign-in of profile {}", profileId);
  }

  /** The registration of {@code profileId} signed in: a rejection mark is lifted. */
  public void accepted(UUID profileId) {
    ownTransaction.executeWithoutResult(status -> profiles.markSignInRejected(profileId, null));
  }

  private String secretOf(ConnectionProfile profile) {
    String ciphertext = profile.getClientSecretCiphertext();
    if (ciphertext == null) {
      return null;
    }
    try {
      return encryptor.decrypt(ciphertext);
    } catch (CredentialsEncryptionKeyMissingException e) {
      log.warn("The client secret of profile {} cannot be decrypted", profile.getId());
      return null;
    }
  }
}
