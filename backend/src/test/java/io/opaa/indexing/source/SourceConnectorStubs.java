package io.opaa.indexing.source;

import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.api.types.PersonalSecretForm;
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
            new Inert(remote(SourceTypes.HTTP_DIRECTORY, "Webverzeichnis")),
            new Inert(remote(SourceTypes.RSS_FEED, "RSS-Feed")),
            new Inert(remote(SourceTypes.CONFLUENCE, "Confluence")),
            new Inert(remote(SourceTypes.S3, "S3-Objektspeicher").withoutDeepLink())));
  }

  /** A remote run-based type admitting profiles, as every shipped one does. */
  private static SourceConnectorDescriptor remote(SourceType type, String displayName) {
    return SourceConnectorDescriptor.remoteRun(type, displayName)
        .withProfiles(
            ProfileDeclaration.of(
                ConnectionProfileSupport.OPTIONAL,
                SignIn.personalSecret(
                    PersonalSecretForm.USERNAME_AND_PASSWORD, ConnectionOwnership.LIBRARY)));
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
