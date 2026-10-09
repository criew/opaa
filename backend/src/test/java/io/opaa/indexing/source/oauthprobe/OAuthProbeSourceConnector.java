package io.opaa.indexing.source.oauthprobe;

import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.indexing.source.ClientAuthentication;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.Endpoint;
import io.opaa.indexing.source.OAuthAuth;
import io.opaa.indexing.source.ProfileDeclaration;
import io.opaa.indexing.source.Revocation;
import io.opaa.indexing.source.ServerAddressRule;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.SourceType;
import io.opaa.test.FakeAuthorizationServer;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * A connector that exists only in test code and signs in persons by OAuth against the shared {@link
 * FakeAuthorizationServer}: authorization code with PKCE, renewal, revocation by RFC 7009. It knows
 * nothing of the flow; its run asks the core for a token like any other secret. It declares the
 * server's issuer without the announcement, so a response may name it or not, but no other.
 */
@Component
public class OAuthProbeSourceConnector implements SourceConnector {

  public static final SourceType TYPE = SourceType.of("OAUTH_PROBE");

  public static final String SCOPES = "files.read offline_access";

  @Override
  public SourceConnectorDescriptor descriptor() {
    FakeAuthorizationServer server = FakeAuthorizationServer.shared();
    return SourceConnectorDescriptor.remoteRun(TYPE, "Testquelle mit Anmeldung beim Anbieter")
        .withProfiles(
            ProfileDeclaration.of(
                    ConnectionProfileSupport.REQUIRED,
                    SignIn.oauth(
                        new OAuthAuth(
                            new Endpoint.Fixed(server.authorizationEndpoint()),
                            new Endpoint.Fixed(server.tokenEndpoint()),
                            new Revocation.Rfc7009(new Endpoint.Fixed(server.revocationEndpoint())),
                            SCOPES,
                            Map.of("access_type", "offline"),
                            ClientAuthentication.CLIENT_SECRET_BASIC,
                            server.issuer(),
                            false),
                        ConnectionOwnership.PERSON))
                .withAddress(ServerAddressRule.schemes("https")));
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    return requested;
  }

  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    return new SourceConnectionTestResult(true, "Testquelle erreichbar.", 0L);
  }
}
