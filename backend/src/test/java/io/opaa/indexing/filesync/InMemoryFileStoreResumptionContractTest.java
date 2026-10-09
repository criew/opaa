package io.opaa.indexing.filesync;

import org.junit.jupiter.api.Nested;

/** The resumption contract against the reference store in the three shapes connectors have. */
class InMemoryFileStoreResumptionContractTest {

  /** Folder markers and file ids that survive a move: the shape of Nextcloud. */
  @Nested
  class TrackedByIdentity extends FileStoreResumptionContract {
    @Override
    protected Fixture fixture() {
      return InMemoryFileStoreContractTest.fixtureOver(
          new InMemoryFileStore().withCheckpoints().withFolderMarkers().withStableIds());
    }
  }

  /**
   * File ids, no folder markers and a change log that proves absence at the round's end: the shape
   * of SharePoint.
   */
  @Nested
  class ProvenByTheChangeLog extends FileStoreResumptionContract {
    @Override
    protected Fixture fixture() {
      return InMemoryFileStoreContractTest.fixtureOver(
          new InMemoryFileStore()
              .withCheckpoints()
              .withStableIds()
              .withChangeFeed()
              .recordingChanges()
              .absenceProof(AbsenceProof.CHANGE_FEED));
    }
  }

  /** Identity by path, absence proven by the round's presence: the shape of SMB. */
  @Nested
  class IdentifiedByLocation extends FileStoreResumptionContract {
    @Override
    protected Fixture fixture() {
      return InMemoryFileStoreContractTest.fixtureOver(
          new InMemoryFileStore().withCheckpoints().absenceProof(AbsenceProof.LOCATION_IDENTITY));
    }
  }
}
