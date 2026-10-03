package io.opaa.architecture.fixture.connectorreadslibrary.indexing.filesync;

import io.opaa.architecture.fixture.connectorreadslibrary.knowledge.KnowledgeLibrary;

public class FileSyncRun {
  String proxy(KnowledgeLibrary library) {
    return library.getSourceProxy();
  }
}
