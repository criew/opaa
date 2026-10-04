package io.opaa.architecture.fixture.personalusage.library;

import io.opaa.architecture.fixture.personalusage.knowledge.PersonalStorageQuota;

/** A view of the administration that reads one person's use. */
public class StorageOverview {
  PersonalStorageQuota quota;

  public long usageOfOwner(String ownerUserId) {
    return quota.usageOf(ownerUserId);
  }
}
