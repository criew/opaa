package io.opaa.architecture.fixture.connectorreadslibrary.indexing.filesync;

import io.opaa.architecture.fixture.connectorreadslibrary.knowledge.KnowledgeLibrary;

public class FileSyncRun {
  boolean insecureSsl(KnowledgeLibrary library) {
    return library.isSourceInsecureSsl();
  }
}
