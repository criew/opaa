package io.opaa.indexing.source;

import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.util.Optional;
import java.util.function.Function;

/**
 * Resolves a library's connection from its own stored fields: what holds for every library without
 * a connection profile. The secret goes out as stored, except for a connector that signs in with a
 * service account key: there the core signs and hands out the access token alone (ADR-0040,
 * Entscheidung 2).
 */
public class LibrarySourceConnectionResolver implements SourceConnectionResolver {

  private final Function<SourceType, Optional<SourceConnector>> connectors;
  private final ServiceAccountTokens tokens;

  /** A resolver for connectors none of which signs in with a service account key. */
  public LibrarySourceConnectionResolver() {
    this(type -> Optional.empty(), null);
  }

  public LibrarySourceConnectionResolver(
      Function<SourceType, Optional<SourceConnector>> connectors, ServiceAccountTokens tokens) {
    this.connectors = connectors;
    this.tokens = tokens;
  }

  @Override
  public SourceSettings resolve(KnowledgeLibrary library) {
    SourceSettings stored = stored(library);
    return signing(library)
        .map(connector -> stored.withCredentials(signed(connector, stored)))
        .orElse(stored);
  }

  @Override
  public SourceSettings resolveForChange(KnowledgeLibrary library) {
    SourceSettings stored = stored(library);
    return signing(library).isPresent() ? stored.withoutCredentials() : stored;
  }

  @Override
  public SourceSettings settingsOnly(KnowledgeLibrary library) {
    return stored(library).withoutCredentials();
  }

  @Override
  public Secret currentSecret(KnowledgeLibrary library) {
    Optional<SourceConnector> signing = signing(library);
    if (signing.isEmpty()) {
      return Secret.personal(library.getSourceCredentials());
    }
    return signed(signing.get(), stored(library));
  }

  /** The access token the core signs with the stored key, {@code null} without a key. */
  private Secret signed(SourceConnector connector, SourceSettings stored) {
    return tokens.secretFor(connector, stored, stored.connectorSettings());
  }

  private Optional<SourceConnector> signing(KnowledgeLibrary library) {
    return connectors
        .apply(library.getSourceType())
        .filter(
            connector -> connector.descriptor().profileDeclaration().serviceAccountKey() != null);
  }

  /** The library's own fields; a stored secret of a signing connector is its key. */
  private SourceSettings stored(KnowledgeLibrary library) {
    return new SourceSettings(
        library.getSourcePath(),
        library.getSourceUrl(),
        library.getSourceProxy(),
        library.getSourceCredentials(),
        library.isSourceInsecureSsl(),
        ConnectorData.storedIn(library),
        signing(library).isPresent() ? SecretKind.SERVICE_ACCOUNT_KEY : null);
  }

  @Override
  public ConnectorData effectiveSettings(KnowledgeLibrary library) {
    return ConnectorData.storedIn(library);
  }
}
