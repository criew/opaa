package io.opaa.architecture.fixture.connectorreadslibrary.indexing.source.confluence;

import io.opaa.architecture.fixture.connectorreadslibrary.indexing.source.ConnectorData;
import io.opaa.architecture.fixture.connectorreadslibrary.knowledge.KnowledgeLibrary;

public class ConfluenceSettings {
  ConnectorData stored(KnowledgeLibrary library) {
    return ConnectorData.storedIn(library);
  }
}
