package io.opaa.indexing.filesync;

import org.junit.jupiter.api.Nested;

/** The resumption contract against the reference store in the two shapes connectors have. */
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
