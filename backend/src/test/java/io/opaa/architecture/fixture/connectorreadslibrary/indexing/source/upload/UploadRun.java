package io.opaa.architecture.fixture.connectorreadslibrary.indexing.source.upload;

import io.opaa.architecture.fixture.connectorreadslibrary.indexing.source.LibrarySourceConnectionResolver;

public class UploadRun {
  Object resolver() {
    return new LibrarySourceConnectionResolver();
  }
}
