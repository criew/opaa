package io.opaa.architecture.fixture.changegate.indexing.registry;

import io.opaa.architecture.fixture.changegate.indexing.spi.SourceConnector;

/** The gate may call every hook. */
public class SourceChangeGate {
  void change(SourceConnector connector, Object library) {
    connector.applyChange(library, connector.validateChange(null, null));
    connector.onSourceChanged(library);
  }
}
