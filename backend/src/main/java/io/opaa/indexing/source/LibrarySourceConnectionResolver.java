package io.opaa.indexing.source;

import io.opaa.knowledge.KnowledgeLibrary;

/**
 * Resolves a library's connection from its own stored fields, the secret as stored: what holds for
 * every library without a connection profile.
 */
public class LibrarySourceConnectionResolver implements SourceConnectionResolver {

  @Override
  public SourceSettings resolve(KnowledgeLibrary library) {
    return new SourceSettings(
        library.getSourcePath(),
        library.getSourceUrl(),
        library.getSourceProxy(),
        library.getSourceCredentials(),
        library.isSourceInsecureSsl(),
        ConnectorData.storedIn(library));
  }

  @Override
  public String currentCredentials(KnowledgeLibrary library) {
    return library.getSourceCredentials();
  }

  @Override
  public ConnectorData effectiveSettings(KnowledgeLibrary library) {
    return ConnectorData.storedIn(library);
  }
}
