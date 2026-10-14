package io.opaa.architecture.fixture.connectorreadslibrary.indexing.source.upload;

import io.opaa.architecture.fixture.connectorreadslibrary.indexing.credentials.LibrarySourceConnectionResolver;

public class UploadRun {
  Object resolver() {
    return new LibrarySourceConnectionResolver();
  }
}
