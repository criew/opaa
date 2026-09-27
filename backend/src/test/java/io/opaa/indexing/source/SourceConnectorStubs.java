package io.opaa.indexing.source;

import io.opaa.knowledge.SourceType;
import io.opaa.test.SourceTypes;
import java.util.List;

/**
 * A registry of inert connectors for the built-in source types, for tests that only read
 * descriptors; run, remote and deep-link abilities match the production connectors.
 */
public final class SourceConnectorStubs {

  private SourceConnectorStubs() {}

  public static SourceConnectorRegistry registry() {
    return new SourceConnectorRegistry(
        List.of(
            new Inert(SourceConnectorDescriptor.acceptingUploads(SourceType.UPLOAD, "Upload")),
            new Inert(SourceConnectorDescriptor.localRun(SourceTypes.FILESYSTEM, "Dateisystem")),
            new Inert(
                SourceConnectorDescriptor.remoteRun(SourceTypes.HTTP_DIRECTORY, "Webverzeichnis")),
            new Inert(SourceConnectorDescriptor.remoteRun(SourceTypes.RSS_FEED, "RSS-Feed")),
            new Inert(SourceConnectorDescriptor.remoteRun(SourceTypes.CONFLUENCE, "Confluence")),
            new Inert(
                SourceConnectorDescriptor.remoteRun(SourceTypes.S3, "S3-Objektspeicher")
                    .withoutDeepLink())));
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
