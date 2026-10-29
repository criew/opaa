package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.asset.AssetShellService;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.connection.PrivateLibraryConnections;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.ErasureCause;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.knowledge.LibraryFolderRepository;
import io.opaa.knowledge.UploadedOriginalRef;
import io.opaa.knowledge.UploadedOriginalStore;
import io.opaa.notification.NotificationService;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The originals step of {@link PrivateLibraryErasure}: older versions and delete markers a
 * versioned store keeps count as left, so the erasure never proves "nothing left" while copies of
 * the originals remain.
 */
class PrivateLibraryErasureOriginalsTest {

  private final UUID organizationId = UUID.randomUUID();
  private final UUID libraryId = UUID.randomUUID();
  private final KnowledgeLibraryRepository libraries = mock(KnowledgeLibraryRepository.class);
  private final VersionedStore store = new VersionedStore();
  private PrivateLibraryErasure erasure;

  @BeforeEach
  void setUp() {
    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    when(library.getId()).thenReturn(libraryId);
    when(library.getOrganizationId()).thenReturn(organizationId);
    when(library.isOwnerOnly()).thenReturn(true);
    when(libraries.findById(libraryId)).thenReturn(Optional.of(library));
    when(libraries.findForErasure(any())).thenReturn(Optional.empty());
    erasure =
        new PrivateLibraryErasure(
            libraries,
            mock(DocumentRepository.class),
            mock(LibraryFolderRepository.class),
            mock(IndexingJobRepository.class),
            mock(SourceSyncStateRepository.class),
            mock(VectorChunkStore.class),
            store,
            List.of(),
            mock(NotificationService.class),
            mock(AssetShellService.class),
            mock(AuditEventRecorder.class),
            mock(PrivateLibraryConnections.class),
            mock(LibraryAccessService.class),
            mock(ApplicationEventPublisher.class),
            mock(PlatformTransactionManager.class),
            Clock.systemUTC());
  }

  @Test
  void retainedVersionsAreErasedBeforeTheLibraryCountsAsErased() {
    store.retained = 2;

    assertThat(erasure.erase(libraryId, ErasureCause.OWNER_REQUEST, null))
        .isEqualTo(PrivateLibraryErasure.Outcome.ERASED);
    assertThat(store.retained).isZero();
  }

  @Test
  void aRetainedVersionThatStaysFailsTheErasureInsteadOfReportingNothingLeft() {
    store.retained = 1;
    store.removable = false;

    assertThatThrownBy(() -> erasure.erase(libraryId, ErasureCause.OWNER_REQUEST, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("1 retained version(s)");
  }

  /** No current originals, {@code retained} older versions that go only when {@code removable}. */
  private static final class VersionedStore implements UploadedOriginalStore {

    int retained;
    boolean removable = true;

    @Override
    public long countRetainedVersions(UUID organizationId, UUID libraryId) {
      return retained;
    }

    @Override
    public int eraseRetainedVersions(UUID organizationId, UUID libraryId) {
      if (!removable) {
        return 0;
      }
      int removed = retained;
      retained = 0;
      return removed;
    }

    @Override
    public AcceptedUpload accept(
        UUID organizationId, UUID libraryId, String extension, InputStream bytes) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<DocumentContent> openForDownload(
        UploadedOriginalRef ref, String fileName, String declaredContentType) {
      return Optional.empty();
    }

    @Override
    public <T> Optional<T> withLocalFile(UploadedOriginalRef ref, Function<Path, T> action) {
      return Optional.empty();
    }

    @Override
    public void delete(UploadedOriginalRef ref) {}

    @Override
    public boolean belongsToLibrary(UploadedOriginalRef ref) {
      return false;
    }

    @Override
    public void forEachStoredOriginal(
        UUID organizationId, UUID libraryId, Consumer<StoredOriginal> visitor) {}

    @Override
    public void forEachStoredLibrary(UUID organizationId, Consumer<UUID> visitor) {}
  }
}
