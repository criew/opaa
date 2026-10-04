package io.opaa.architecture.fixture.personalusage.searchadmin;

import io.opaa.architecture.fixture.personalusage.knowledge.DocumentRepository;

/** A report that sums one person's private libraries past the quota. */
public class StorageReport {
  DocumentRepository documents;

  public long sumOf(String ownerUserId) {
    return documents.sumFileSizeOfPrivateLibrariesOwnedBy(ownerUserId);
  }
}
