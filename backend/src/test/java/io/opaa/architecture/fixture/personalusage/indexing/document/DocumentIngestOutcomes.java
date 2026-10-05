package io.opaa.architecture.fixture.personalusage.indexing.document;

import io.opaa.architecture.fixture.personalusage.knowledge.LibraryStorageQuotaService;

/** The protocol texts of the intake ask the enforcement for the owner's message. */
public class DocumentIngestOutcomes {
  LibraryStorageQuotaService enforcement;

  public String rejection(String ownerUserId) {
    return enforcement.personalQuotaExceededMessage(ownerUserId);
  }
}
