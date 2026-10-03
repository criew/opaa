package io.opaa.architecture.fixture.connectorreadslibrary.indexing.source;

import io.opaa.architecture.fixture.connectorreadslibrary.knowledge.KnowledgeLibrary;

public class LibrarySourceConnectionResolver implements SourceConnectionResolver {
  @Override
  public String currentCredentials(KnowledgeLibrary library) {
    return library.getSourceCredentials();
  }
}
