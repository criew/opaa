package io.opaa.architecture.fixture.connectorreadslibrary.indexing.source;

import io.opaa.architecture.fixture.connectorreadslibrary.knowledge.KnowledgeLibrary;

public class ConnectorData {
  public static ConnectorData storedIn(KnowledgeLibrary library) {
    return library.getSourceSettings() == null ? null : new ConnectorData();
  }
}
