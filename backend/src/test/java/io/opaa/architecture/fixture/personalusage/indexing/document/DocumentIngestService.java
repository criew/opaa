package io.opaa.architecture.fixture.personalusage.indexing.document;

import io.opaa.architecture.fixture.personalusage.knowledge.LibraryStorageQuotaService;

/** The intake asks the enforcement for its verdict. */
public class DocumentIngestService {
  LibraryStorageQuotaService enforcement;

  public boolean admit(String ownerUserId) {
    return !enforcement.verdictFor(ownerUserId, 0);
  }
}
