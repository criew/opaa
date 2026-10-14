package io.opaa.architecture.fixture.connectorreadslibrary.indexing.credentials;

import io.opaa.architecture.fixture.connectorreadslibrary.knowledge.KnowledgeLibrary;

public interface SourceConnectionResolver {
  String currentCredentials(KnowledgeLibrary library);
}
