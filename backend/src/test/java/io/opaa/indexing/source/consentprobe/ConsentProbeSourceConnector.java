package io.opaa.indexing.source.consentprobe;

import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.indexing.source.ClientAuthentication;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.Endpoint;
import io.opaa.indexing.source.OAuthAuth;
import io.opaa.indexing.source.ProfileDeclaration;
import io.opaa.indexing.source.Revocation;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SecretKind;
import io.opaa.indexing.source.ServerAddressRule;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SourceBrowser;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import io.opaa.test.FakeAuthorizationServer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * A connector that exists only in test code and whose source a library connects with a service
 * account ("Quelle verbinden") by OAuth against the shared {@link FakeAuthorizationServer}. It
 * names the account the access token was issued to, lists one folder for a token the server issued
 * and forgets the run state of a library whose account changed. It knows nothing of the flow.
 */
@Component
public class ConsentProbeSourceConnector implements SourceConnector, SourceBrowser {

  public static final SourceType TYPE = SourceType.of("CONSENT_PROBE");

  public static final String SCOPES = "files.read offline_access";

  /** The one folder its listing names for a token the server issued. */
  public static final String LISTED_FOLDER = "bauamt";

  private final SourceSyncStateRepository states;

  public ConsentProbeSourceConnector(SourceSyncStateRepository states) {
    this.states = states;
  }

  @Override
  public SourceConnectorDescriptor descriptor() {
    FakeAuthorizationServer server = FakeAuthorizationServer.shared();
    return SourceConnectorDescriptor.remoteRun(TYPE, "Testquelle mit Dienstkonto")
        .withProfiles(
            ProfileDeclaration.of(
                    ConnectionProfileSupport.REQUIRED,
                    SignIn.oauth(
                        new OAuthAuth(
                            new Endpoint.Fixed(server.authorizationEndpoint()),
                            new Endpoint.Fixed(server.tokenEndpoint()),
                            new Revocation.Rfc7009(new Endpoint.Fixed(server.revocationEndpoint())),
                            SCOPES,
                            Map.of(),
                            ClientAuthentication.CLIENT_SECRET_BASIC),
                        ConnectionOwnership.LIBRARY))
                .withAddress(ServerAddressRule.schemes("https")));
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    return requested;
  }

  @Override
  public Optional<String> connectedAccount(SourceSettings settings) {
    return Optional.ofNullable(FakeAuthorizationServer.shared().accountOf(tokenOf(settings)));
  }

  @Override
  public void onSourceChanged(
      KnowledgeLibrary library, boolean addressChanged, Set<String> changedSettings) {
    if (addressChanged) {
      states.deleteByLibraryId(library.getId());
    }
  }

  @Override
  public String otherTypeMessage() {
    return "sourceType passt nicht zur Testquelle mit Dienstkonto";
  }

  @Override
  public SourceListing browse(Query query) {
    if (FakeAuthorizationServer.shared().accountOf(tokenOf(query.settings())) != null) {
      return new SourceListing(
          true, List.of(new SourceListing.Entry(LISTED_FOLDER, LISTED_FOLDER)), null);
    }
    return new SourceListing(false, List.of(), "Die Quelle ist nicht verbunden.");
  }

  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    return FakeAuthorizationServer.shared().accountOf(tokenOf(settings)) != null
        ? new SourceConnectionTestResult(true, "Testquelle mit Dienstkonto erreichbar.", 0L)
        : new SourceConnectionTestResult(
            false, "Die Quelle ist nicht verbunden.", null, false, null);
  }

  private static String tokenOf(SourceSettings settings) {
    Secret secret = settings.credentials();
    return secret == null || secret.kind() != SecretKind.ACCESS_TOKEN ? null : secret.value();
  }
}
