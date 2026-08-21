package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.DocumentRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link LibraryStorageQuotaService} (#119). */
class LibraryStorageQuotaServiceTest {

  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final UUID libraryId = UUID.randomUUID();

  private LibraryStorageQuotaService service(long quotaBytes) {
    UploadProperties properties = new UploadProperties(null, 0, null, 0, quotaBytes);
    return new LibraryStorageQuotaService(documentRepository, properties);
  }

  @Test
  void defaultsToTenGibWhenNoQuotaIsConfigured() {
    // UploadProperties itself defaults libraryQuotaBytes to 10 GiB when <= 0 - covered here via
    // the service's own quotaBytes() accessor.
    LibraryStorageQuotaService quotaService = service(0);
    assertThat(quotaService.quotaBytes()).isEqualTo(10L * 1024 * 1024 * 1024);
  }

  @Test
  void wouldExceedQuotaIsFalseExactlyAtTheLimit() {
    // Grenzfall (#119 acceptance criteria): a document that fills the library exactly up to its
    // quota is accepted, only the first byte past it is rejected.
    LibraryStorageQuotaService quotaService = service(1000);
    when(documentRepository.sumFileSizeByLibraryId(libraryId)).thenReturn(400L);

    assertThat(quotaService.wouldExceedQuota(libraryId, 600)).isFalse();
    assertThat(quotaService.wouldExceedQuota(libraryId, 601)).isTrue();
  }

  @Test
  void quotaExceededMessageNamesUsedAndTotalQuotaInGermanFormattedGigabytes() {
    LibraryStorageQuotaService quotaService = service(10L * 1024 * 1024 * 1024);
    when(documentRepository.sumFileSizeByLibraryId(libraryId)).thenReturn(3L * 1024 * 1024 * 1024);

    assertThat(quotaService.quotaExceededMessage(libraryId))
        .isEqualTo("Speicherkontingent der Bibliothek erschöpft (3,0 GB von 10,0 GB belegt)");
  }
}
