package io.opaa.architecture.fixture.personalusage.indexing.document;

import io.opaa.architecture.fixture.personalusage.knowledge.LibraryStorageQuotaService;

/** The intake asks the enforcement for its verdict and message. */
public class DocumentIngestService {
  LibraryStorageQuotaService enforcement;

  public String admit(String ownerUserId) {
    return enforcement.verdictFor(ownerUserId, 0)
        ? enforcement.personalQuotaExceededMessage(ownerUserId)
        : null;
  }
}
