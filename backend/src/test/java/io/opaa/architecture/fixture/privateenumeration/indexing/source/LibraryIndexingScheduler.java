package io.opaa.architecture.fixture.privateenumeration.indexing.source;

import io.opaa.architecture.fixture.privateenumeration.knowledge.KnowledgeLibraryRepository;
import java.util.List;

/** A listed system process: it runs every scheduled library and names none. */
public class LibraryIndexingScheduler {
  KnowledgeLibraryRepository libraries;

  public List<Object> due() {
    return libraries.findByScheduleEnabledTrue();
  }
}
