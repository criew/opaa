package io.opaa.architecture.fixture.changegate.indexing.source;

/** The gate may call every hook. */
public class SourceChangeGate {
  void change(SourceConnector connector, Object library) {
    connector.applyChange(library, connector.validateChange(null, null));
    connector.onSourceChanged(library);
  }
}
