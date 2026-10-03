package io.opaa.architecture.fixture.connectorreadslibrary.indexing.source.rss;

import io.opaa.architecture.fixture.connectorreadslibrary.knowledge.KnowledgeLibrary;
import java.util.function.Function;

public class FeedRun {
  Function<KnowledgeLibrary, String> address() {
    return KnowledgeLibrary::getSourceUrl;
  }
}
