package io.opaa.architecture.fixture.personalusage.knowledge;

/** The enforcement reads the owner's use. */
public class LibraryStorageQuotaService {
  PersonalStorageQuota quota;

  public boolean verdictFor(String ownerUserId, long limit) {
    return quota.usageOf(ownerUserId) > limit;
  }

  public String personalQuotaExceededMessage(String ownerUserId) {
    return quota.usageOf(ownerUserId) + " belegt";
  }
}
