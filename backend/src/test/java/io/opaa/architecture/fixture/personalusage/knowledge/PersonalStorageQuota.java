package io.opaa.architecture.fixture.personalusage.knowledge;

/** The quota itself reads the raw sum. */
public class PersonalStorageQuota {
  DocumentRepository documents;

  public long usageOf(String userId) {
    return documents.sumFileSizeOfPrivateLibrariesOwnedBy(userId);
  }
}
