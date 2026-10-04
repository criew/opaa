package io.opaa.architecture.fixture.documentdelete.indexing.filesync;

import io.opaa.architecture.fixture.documentdelete.indexing.maintenance.StaleDocumentCleanupService;

/** A run body that removes a document the source confirmed gone. */
public class FileSync {
  void removeGone(StaleDocumentCleanupService cleanupService, Object document) {
    cleanupService.removeWithAttachments(document);
  }
}
