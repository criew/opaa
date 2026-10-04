package io.opaa.indexing.source.profileprobe;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.api.types.PersonalSecretForm;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.DefaultKey;
import io.opaa.indexing.source.ProfileDeclaration;
import io.opaa.indexing.source.ServerAddressRule;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * A connector that exists only in test code and admits connection profiles: it reports its profile
 * declaration in its descriptor and needs no line outside this package. Its settings {@code
 * edition} and {@code topic} are optional texts; a profile may set {@code edition}, at an {@code
 * https}, {@code http} or {@code smb} address.
 */
@Component
public class ProfileProbeSourceConnector implements SourceConnector {

  public static final SourceType TYPE = SourceType.of("PROFILE_PROBE");

  private static final Set<String> KEYS = Set.of("edition", "topic");

  @Override
  public SourceConnectorDescriptor descriptor() {
    return SourceConnectorDescriptor.remoteRun(TYPE, "Testquelle mit Zugang")
        .withProfiles(
            ProfileDeclaration.of(
                    ConnectionProfileSupport.OPTIONAL,
                    SignIn.of(
                        ConnectionAuthMethod.NONE,
                        ConnectionOwnership.LIBRARY,
                        ConnectionOwnership.PERSON),
                    SignIn.personalSecret(
                        PersonalSecretForm.USERNAME_AND_PASSWORD, ConnectionOwnership.LIBRARY))
                .withAddress(ServerAddressRule.schemes("https", "http", "smb"))
                .withDefaults(DefaultKey.choice("edition", "Edition", "CLOUD", "DC")));
  }

  @Override
  public Set<String> settingsKeys() {
    return KEYS;
  }

  @Override
  public ConnectorData readSettings(ConnectorData requested) {
    requested.requireOnly(KEYS);
    for (String key : KEYS) {
      if (requested.has(key) && !(requested.get(key) instanceof String)) {
        throw new ValidationException("sourceSettings." + key + " muss ein Text sein");
      }
    }
    return requested.isEmpty() ? null : requested;
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    if (requested.sourceUrl() == null) {
      throw new ValidationException("sourceUrl ist erforderlich");
    }
    return requested;
  }

  @Override
  public void configureNew(KnowledgeLibrary library, SourceSettings validated) {
    if (validated.connectorSettings() != null) {
      library.updateSourceSettings(validated.connectorSettings().toJson());
    }
  }

  @Override
  public void applyChange(
      KnowledgeLibrary library, ConnectorData stored, SourceSettings validated) {
    if (validated.connectorSettings() != null) {
      library.updateSourceSettings(validated.connectorSettings().toJson());
    }
  }

  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    return new SourceConnectionTestResult(true, "Testquelle mit Zugang erreichbar.", 0L);
  }
}
