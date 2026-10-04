package io.opaa.architecture.fixture.changegate.indexing.source;

public interface SourceConnector {
  Object validateChange(Object before, Object requested);

  void applyChange(Object library, Object validated);

  void onSourceChanged(Object library);
}
