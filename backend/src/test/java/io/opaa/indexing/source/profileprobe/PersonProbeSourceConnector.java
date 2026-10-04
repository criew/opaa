package io.opaa.indexing.source.profileprobe;

import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.api.types.PersonalSecretForm;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.ProfileDeclaration;
import io.opaa.indexing.source.ServerAddressRule;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.SourceType;
import org.springframework.stereotype.Component;

/**
 * A connector that exists only in test code and lets persons connect their own account: its
 * personal secret is a user name and a password, owned by a library or a person. Its sign-in takes
 * only the password {@link #ACCEPTED_PASSWORD}; any other is rejected as a provider would.
 */
@Component
public class PersonProbeSourceConnector implements SourceConnector {

  public static final SourceType TYPE = SourceType.of("PERSON_PROBE");

  /** The one password the probe's sign-in accepts, for any user name. */
  public static final String ACCEPTED_PASSWORD = "richtig-2163";

  @Override
  public SourceConnectorDescriptor descriptor() {
    return SourceConnectorDescriptor.remoteRun(TYPE, "Testquelle für Personen")
        .withProfiles(
            ProfileDeclaration.of(
                    ConnectionProfileSupport.OPTIONAL,
                    SignIn.personalSecret(
                        PersonalSecretForm.USERNAME_AND_PASSWORD,
                        ConnectionOwnership.LIBRARY,
                        ConnectionOwnership.PERSON))
                .withAddress(ServerAddressRule.schemes("https")));
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    return requested;
  }

  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    String credentials = settings.sourceCredentials();
    if (credentials != null && credentials.endsWith(":" + ACCEPTED_PASSWORD)) {
      return new SourceConnectionTestResult(true, "Testquelle für Personen erreichbar.", 0L);
    }
    return new SourceConnectionTestResult(
        false, "Die Anmeldung bei der Testquelle wurde abgelehnt.", null, false, null);
  }
}
