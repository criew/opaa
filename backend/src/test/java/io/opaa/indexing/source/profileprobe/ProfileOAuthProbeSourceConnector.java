package io.opaa.indexing.source.profileprobe;

import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.common.ValidationException;
import io.opaa.indexing.spi.ClientAuthentication;
import io.opaa.indexing.spi.ClientCredentialsAuth;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.Endpoint;
import io.opaa.indexing.spi.OAuthAuth;
import io.opaa.indexing.spi.ProfileDeclaration;
import io.opaa.indexing.spi.Revocation;
import io.opaa.indexing.spi.SignIn;
import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceConnector;
import io.opaa.indexing.spi.SourceConnectorDescriptor;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.knowledge.SourceType;
import java.net.URI;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * A test-only connector offering sign-ins whose app registration only a profile holds - OAuth and
 * client credentials against {@link #TOKEN_ENDPOINT} - and therefore requiring profiles (ADR-0038).
 * It has no run and no settings.
 */
@Component
public class ProfileOAuthProbeSourceConnector implements SourceConnector {

  public static final SourceType TYPE = SourceType.of("PROFILE_OAUTH_PROBE");

  public static final URI TOKEN_ENDPOINT = URI.create("https://login.example.org/token");

  public static final URI AUTHORIZATION_ENDPOINT =
      URI.create("https://login.example.org/authorize");

  @Override
  public SourceConnectorDescriptor descriptor() {
    return new SourceConnectorDescriptor(
            TYPE, "Testquelle mit OAuth", false, true, false, false, null, null)
        .withProfiles(
            ProfileDeclaration.of(
                ConnectionProfileSupport.REQUIRED,
                SignIn.oauth(
                    new OAuthAuth(
                        new Endpoint.Fixed(AUTHORIZATION_ENDPOINT),
                        new Endpoint.Fixed(TOKEN_ENDPOINT),
                        new Revocation.None(),
                        "probe.read",
                        Map.of(),
                        ClientAuthentication.CLIENT_SECRET_BASIC),
                    ConnectionOwnership.LIBRARY,
                    ConnectionOwnership.PERSON),
                SignIn.clientCredentials(
                    new ClientCredentialsAuth(
                        new Endpoint.Fixed(TOKEN_ENDPOINT),
                        "probe.read",
                        ClientAuthentication.CLIENT_SECRET_BASIC))));
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    if (requested.sourceUrl() == null) {
      throw new ValidationException("sourceUrl ist erforderlich");
    }
    return requested;
  }

  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    return new SourceConnectionTestResult(true, "Testquelle mit OAuth erreichbar.", 0L);
  }
}
