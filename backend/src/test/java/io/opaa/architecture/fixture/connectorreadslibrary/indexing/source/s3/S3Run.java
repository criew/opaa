package io.opaa.architecture.fixture.connectorreadslibrary.indexing.source.s3;

import io.opaa.architecture.fixture.connectorreadslibrary.knowledge.KnowledgeLibrary;

public class S3Run {
  String credentials(KnowledgeLibrary library) {
    return library.getSourceCredentials();
  }
}
