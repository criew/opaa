package io.opaa.architecture.fixture.documentdelete.indexing.maintenance;

/** Stands in for the cleanup service: three removing methods and a reading one. */
public class StaleDocumentCleanupService {
  public int reconcile(Object library) {
    return 0;
  }

  public int cleanupVanished(Object library) {
    return 0;
  }

  public void removeWithAttachments(Object document) {}

  public static void foldInPreservedAttachmentPaths(Object documents) {}
}
