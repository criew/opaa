package io.opaa.architecture.fixture.documentdelete.indexing.spi;

/** Stands in for the port the run frame removes through. */
public interface VanishedDocumentReconciler {
  void removeWithAttachments(Object document);
}
