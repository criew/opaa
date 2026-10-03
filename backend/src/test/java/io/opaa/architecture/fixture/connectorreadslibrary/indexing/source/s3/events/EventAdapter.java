package io.opaa.architecture.fixture.connectorreadslibrary.indexing.source.s3.events;

import io.opaa.architecture.fixture.connectorreadslibrary.knowledge.KnowledgeLibrary;

public class EventAdapter {
  String secret(KnowledgeLibrary library) {
    return library.getWebhookSecret();
  }
}
