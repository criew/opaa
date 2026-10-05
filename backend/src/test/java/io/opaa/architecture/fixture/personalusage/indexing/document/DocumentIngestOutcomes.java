package io.opaa.architecture.fixture.personalusage.indexing.document;

import io.opaa.architecture.fixture.personalusage.knowledge.LibraryStorageQuotaService;
import java.util.function.Supplier;

/** The protocol texts of the intake ask the enforcement for the owner's message. */
public class DocumentIngestOutcomes {
  LibraryStorageQuotaService enforcement;

  public String rejection(String ownerUserId) {
    return enforcement.personalQuotaExceededMessage(ownerUserId);
  }

  /** The factory handing the owner's message on. */
  public record QuotaMessages(Supplier<String> person) {
    public static QuotaMessages of(LibraryStorageQuotaService quota, String ownerUserId) {
      return new QuotaMessages(() -> quota.personalQuotaExceededMessage(ownerUserId));
    }
  }
}
