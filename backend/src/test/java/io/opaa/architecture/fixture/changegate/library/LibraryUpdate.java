package io.opaa.architecture.fixture.changegate.library;

import io.opaa.architecture.fixture.changegate.indexing.source.SourceConnector;
import io.opaa.architecture.fixture.changegate.indexing.source.probe.ProbeConnector;

/** Validates through the interface and discards run state through an implementation. */
public class LibraryUpdate {
  Object update(SourceConnector connector, ProbeConnector probe, Object library) {
    Object validated = connector.validateChange(null, library);
    probe.onSourceChanged(library);
    return validated;
  }
}
