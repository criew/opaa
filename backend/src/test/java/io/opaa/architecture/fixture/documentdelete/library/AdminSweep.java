package io.opaa.architecture.fixture.documentdelete.library;

import io.opaa.architecture.fixture.documentdelete.indexing.maintenance.StaleDocumentCleanupService;
import java.util.function.Consumer;

/**
 * Removes through the cleanup service outside a run, directly and by method reference; folding in
 * attachment paths removes nothing.
 */
public class AdminSweep {
  Consumer<Object> sweep(StaleDocumentCleanupService cleanupService, Object library) {
    StaleDocumentCleanupService.foldInPreservedAttachmentPaths(library);
    cleanupService.cleanupVanished(library);
    return cleanupService::removeWithAttachments;
  }
}
