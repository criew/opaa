package io.opaa.architecture.fixture.personalusage.library.web;

import io.opaa.architecture.fixture.personalusage.knowledge.PersonalStorageQuota;

/** The person's own view reads her use. */
public abstract class MyPrivateStorageController {
  PersonalStorageQuota quota;

  public long mine(String callerId) {
    return quota.usageOf(callerId);
  }
}
