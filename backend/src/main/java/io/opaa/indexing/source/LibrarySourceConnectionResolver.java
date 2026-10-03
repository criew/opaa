package io.opaa.indexing.source;

import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.util.Optional;
import java.util.function.Function;

/**
 * Resolves a library's connection from its own stored fields. The secret goes out as stored, except
 * for a connector that signs in with a service account key: there the core signs and hands out the
 * access token alone (ADR-0040, Entscheidung 2). The only resolver until connection profiles exist
 * (#2160).
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
        .map(
            connector ->
                stored.withSourceCredentials(
                    tokens.forConnector(connector, stored, stored.connectorSettings())))
        .orElse(stored);
  }

  @Override
  public SourceSettings resolveForChange(KnowledgeLibrary library) {
    SourceSettings stored = stored(library);
    return signing(library).isPresent() ? stored.withoutCredentials() : stored;
  }

  @Override
  public String currentCredentials(KnowledgeLibrary library) {
    Optional<SourceConnector> signing = signing(library);
    if (signing.isEmpty()) {
      return library.getSourceCredentials();
    }
    SourceSettings stored = stored(library);
    return tokens.forConnector(signing.get(), stored, stored.connectorSettings());
  }

  private Optional<SourceConnector> signing(KnowledgeLibrary library) {
    return connectors
        .apply(library.getSourceType())
        .filter(connector -> connector.descriptor().serviceAccountKey() != null);
  }

  private static SourceSettings stored(KnowledgeLibrary library) {
    return new SourceSettings(
        library.getSourcePath(),
        library.getSourceUrl(),
        library.getSourceProxy(),
        library.getSourceCredentials(),
        library.isSourceInsecureSsl(),
        ConnectorData.storedIn(library));
  }
}
