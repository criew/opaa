package io.opaa.architecture.fixture.changegate.indexing.source.probe;

import io.opaa.architecture.fixture.changegate.indexing.source.SourceConnector;

public class ProbeConnector implements SourceConnector {
  @Override
  public Object validateChange(Object before, Object requested) {
    return requested;
  }

  @Override
  public void applyChange(Object library, Object validated) {}

  @Override
  public void onSourceChanged(Object library) {}
}
