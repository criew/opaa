package io.opaa.architecture.fixture.connectorreadslibrary.indexing.source;

import io.opaa.architecture.fixture.connectorreadslibrary.knowledge.KnowledgeLibrary;

public class SourceResolver {
  String resolve(KnowledgeLibrary library) {
    return library.getSourceUrl() + library.getSourceCredentials();
  }
}
