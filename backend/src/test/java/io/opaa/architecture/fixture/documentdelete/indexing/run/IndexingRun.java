package io.opaa.architecture.fixture.documentdelete.indexing.run;

import io.opaa.architecture.fixture.documentdelete.indexing.maintenance.StaleDocumentCleanupService;

/** The run frame, which removes a document the source confirmed gone. */
public class IndexingRun {
  void removeGone(StaleDocumentCleanupService cleanupService, Object document) {
    cleanupService.removeWithAttachments(document);
  }
}
