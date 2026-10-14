package io.opaa.architecture.fixture.documentdelete.indexing.source.acme;

import io.opaa.architecture.fixture.documentdelete.indexing.source.VanishedDocumentReconciler;

/** A connector that removes through the port itself instead of through its run. */
public class Sweep {
  void removeGone(VanishedDocumentReconciler reconciler, Object document) {
    reconciler.removeWithAttachments(document);
  }
}
