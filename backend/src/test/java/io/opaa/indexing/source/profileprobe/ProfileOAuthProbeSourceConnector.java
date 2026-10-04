package io.opaa.indexing.source.profileprobe;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.ProfileDeclaration;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.SourceType;
import org.springframework.stereotype.Component;

/**
 * A test-only connector offering sign-ins whose app registration only a profile holds - OAuth and
 * client credentials - and therefore requiring profiles (ADR-0038). It has no run and no settings.
 */
@Component
public class ProfileOAuthProbeSourceConnector implements SourceConnector {

  public static final SourceType TYPE = SourceType.of("PROFILE_OAUTH_PROBE");

  @Override
  public SourceConnectorDescriptor descriptor() {
    return new SourceConnectorDescriptor(
            TYPE, "Testquelle mit OAuth", false, true, false, false, null, null)
        .withProfiles(
            ProfileDeclaration.of(
                ConnectionProfileSupport.REQUIRED,
                SignIn.of(
                    ConnectionAuthMethod.OAUTH,
                    ConnectionOwnership.LIBRARY,
                    ConnectionOwnership.PERSON),
                SignIn.of(ConnectionAuthMethod.CLIENT_CREDENTIALS, ConnectionOwnership.LIBRARY)));
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
