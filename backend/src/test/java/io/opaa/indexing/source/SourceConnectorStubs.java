package io.opaa.indexing.source;

import io.opaa.api.types.DocumentSourceType;
import java.util.Arrays;

/** A registry of inert connectors, one per source type, for tests that only read descriptors. */
public final class SourceConnectorStubs {

  private SourceConnectorStubs() {}

  public static SourceConnectorRegistry registry() {
    return new SourceConnectorRegistry(
        Arrays.stream(DocumentSourceType.values())
            .map(
                type ->
                    (SourceConnector)
                        new Inert(
                            new SourceConnectorDescriptor(type, type.hasIndexingRun(), null, null)))
            .toList());
  }

  private record Inert(SourceConnectorDescriptor descriptor) implements SourceConnector {

    @Override
    public SourceSettings validate(SourceSettings requested) {
      return requested;
    }

    @Override
    public SourceConnectionTestResult testConnection(
        SourceSettings settings, ConnectorData stored) {
      throw new UnsupportedOperationException();
    }
  }
}
