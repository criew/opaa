package io.opaa.architecture.fixture.personalusage.knowledge;

/** The raw sum of one person's private libraries. */
public interface DocumentRepository {
  long sumFileSizeOfPrivateLibrariesOwnedBy(String ownerUserId);
}
