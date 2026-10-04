package io.opaa.architecture.fixture.personalusage.library;

import io.opaa.architecture.fixture.personalusage.knowledge.LibraryStorageQuotaService;
import io.opaa.architecture.fixture.personalusage.knowledge.PersonalStorageQuota;

/** A view of the administration that reads one person's use, directly and through the message. */
public class StorageOverview {
  PersonalStorageQuota quota;
  LibraryStorageQuotaService enforcement;

  public String messageOfOwner(String ownerUserId) {
    return enforcement.personalQuotaExceededMessage(ownerUserId);
  }

  public long usageOfOwner(String ownerUserId) {
    return quota.usageOf(ownerUserId);
  }
}
