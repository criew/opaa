package io.opaa.architecture.fixture.connectorreadslibrary.indexing.source.filesystem;

import io.opaa.architecture.fixture.connectorreadslibrary.knowledge.KnowledgeLibrary;

public class Walker {
  String root(KnowledgeLibrary library) {
    return library.getSourcePath();
  }
}
