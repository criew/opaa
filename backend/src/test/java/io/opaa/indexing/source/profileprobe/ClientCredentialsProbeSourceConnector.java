package io.opaa.indexing.source.profileprobe;

import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.indexing.source.ClientAuthentication;
import io.opaa.indexing.source.ClientCredentialsAuth;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.Endpoint;
import io.opaa.indexing.source.ProfileDeclaration;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.SourceType;

/**
 * A test-only connector signing in with the client credentials of its profile at {@code token} - a
 * fake authorization server - authenticating the client as {@code clientAuth} says. It declares
 * nothing outside this package and is registered by the test that builds it.
 */
public class ClientCredentialsProbeSourceConnector implements SourceConnector {

  public static final SourceType TYPE = SourceType.of("CLIENT_PROBE");

  /** The scope asked for when the profile names none. */
  public static final String DEFAULT_SCOPE = "probe.read";

  private final Endpoint token;
  private final ClientAuthentication clientAuth;

  public ClientCredentialsProbeSourceConnector(Endpoint token, ClientAuthentication clientAuth) {
    this.token = token;
    this.clientAuth = clientAuth;
  }

  @Override
  public SourceConnectorDescriptor descriptor() {
    return new SourceConnectorDescriptor(
            TYPE, "Testquelle mit Client-Credentials", false, true, false, false, null, null)
        .withProfiles(
            ProfileDeclaration.of(
                ConnectionProfileSupport.REQUIRED,
                SignIn.clientCredentials(
                    new ClientCredentialsAuth(token, DEFAULT_SCOPE, clientAuth))));
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    return requested;
  }

  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    return new SourceConnectionTestResult(
        true, "Testquelle mit Client-Credentials erreichbar.", 0L);
  }
}
