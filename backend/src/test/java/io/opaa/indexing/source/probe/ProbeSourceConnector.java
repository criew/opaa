package io.opaa.indexing.source.probe;

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
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * A connector that exists only in test code: registered by component scan like any connector bean,
 * without a line of production code naming it (ADR-0038). It has no run and reads nothing; its one
 * setting {@code topic} is required and travels back as the connection test's finding.
 */
@Component
public class ProbeSourceConnector implements SourceConnector {

  public static final SourceType TYPE = SourceType.of("PROBE");

  private static final String TOPIC = "topic";

  @Override
  public SourceConnectorDescriptor descriptor() {
    return new SourceConnectorDescriptor(TYPE, "Testquelle", false, true, false, false, null, null)
        .withProfiles(
            ProfileDeclaration.of(
                ConnectionProfileSupport.OPTIONAL,
                SignIn.of(ConnectionAuthMethod.NONE, ConnectionOwnership.LIBRARY)));
  }

  @Override
  public Set<String> settingsKeys() {
    return Set.of(TOPIC);
  }

  @Override
  public ConnectorData readSettings(ConnectorData requested) {
    requested.requireOnly(Set.of(TOPIC));
    if (!(requested.get(TOPIC) instanceof String topic) || topic.isBlank()) {
      throw new ValidationException("sourceSettings.topic muss ein nicht leerer Text sein");
    }
    return requested;
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    if (requested.connectorSettings() == null) {
      throw new ValidationException("sourceSettings.topic ist erforderlich");
    }
    return requested;
  }

  @Override
  public void configureNew(KnowledgeLibrary library, SourceSettings validated) {
    library.updateSourceSettings(validated.connectorSettings().toJson());
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
    ConnectorData topic =
        settings.connectorSettings() != null ? settings.connectorSettings() : stored;
    return new SourceConnectionTestResult(true, "Testquelle erreichbar.", 0L, null, topic);
  }
}
