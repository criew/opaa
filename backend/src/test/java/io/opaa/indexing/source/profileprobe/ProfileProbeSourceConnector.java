package io.opaa.indexing.source.profileprobe;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
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
 * support and sign-in methods in its descriptor and needs no line outside this package (#2160). Its
 * settings {@code edition} and {@code topic} are optional texts.
 */
@Component
public class ProfileProbeSourceConnector implements SourceConnector {

  public static final SourceType TYPE = SourceType.of("PROFILE_PROBE");

  private static final Set<String> KEYS = Set.of("edition", "topic");

  @Override
  public SourceConnectorDescriptor descriptor() {
    return SourceConnectorDescriptor.remoteRun(TYPE, "Testquelle mit Zugang")
        .withProfiles(
            ConnectionProfileSupport.OPTIONAL,
            Set.of(
                ConnectionAuthMethod.NONE,
                ConnectionAuthMethod.PERSONAL_SECRET,
                ConnectionAuthMethod.OAUTH));
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
