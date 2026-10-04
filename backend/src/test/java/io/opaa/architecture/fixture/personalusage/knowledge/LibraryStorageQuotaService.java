package io.opaa.architecture.fixture.personalusage.knowledge;

/** The enforcement reads the owner's use. */
public class LibraryStorageQuotaService {
  PersonalStorageQuota quota;

  public boolean exhausted(String ownerUserId, long limit) {
    return quota.usageOf(ownerUserId) > limit;
  }
}
