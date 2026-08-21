package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
  void zeroOrNegativeQuotaConfigurationMeansUnlimited() {
    // #119, PR #700 review finding 2: 0/negative is a real "unbegrenzt" configuration, not a
    // silent fallback to the 10 GiB default - important for an existing library already larger
    // than the default, which would otherwise be permanently blocked the moment this quota ships.
    LibraryStorageQuotaService zeroQuotaService = service(0);
    when(documentRepository.sumFileSizeByLibraryId(libraryId)).thenReturn(50L * 1024 * 1024 * 1024);
    assertThat(zeroQuotaService.quotaBytes()).isZero();
    assertThat(zeroQuotaService.wouldExceedQuota(libraryId, 1024)).isFalse();

    LibraryStorageQuotaService negativeQuotaService = service(-1);
    assertThat(negativeQuotaService.quotaBytes()).isNegative();
    assertThat(negativeQuotaService.wouldExceedQuota(libraryId, Long.MAX_VALUE / 2)).isFalse();
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
  void quotaExceededMessageNamesUsedAndTotalQuotaAdaptivelyFormatted() {
    // PR #700 review finding 3: adaptive units (mirrors the frontend's formatFileSize), so a
    // sub-GB quota never reads as the indistinguishable "0,0 GB von 0,0 GB belegt".
    LibraryStorageQuotaService gibQuotaService = service(10L * 1024 * 1024 * 1024);
    when(documentRepository.sumFileSizeByLibraryId(libraryId)).thenReturn(3L * 1024 * 1024 * 1024);
    assertThat(gibQuotaService.quotaExceededMessage(libraryId))
        .isEqualTo("Speicherkontingent der Bibliothek erschöpft (3,0 GB von 10,0 GB belegt)");

    LibraryStorageQuotaService smallQuotaService = service(200L * 1024 * 1024);
    when(documentRepository.sumFileSizeByLibraryId(libraryId)).thenReturn(150L * 1024 * 1024);
    assertThat(smallQuotaService.quotaExceededMessage(libraryId))
        .isEqualTo("Speicherkontingent der Bibliothek erschöpft (150,0 MB von 200,0 MB belegt)");
  }

  @Test
  void quotaExceededMessageOverloadAvoidsARecomputationWhenTheCallerAlreadyKnowsUsedBytes() {
    LibraryStorageQuotaService quotaService = service(10L * 1024 * 1024 * 1024);

    assertThat(quotaService.quotaExceededMessage(libraryId, 3L * 1024 * 1024 * 1024))
        .isEqualTo("Speicherkontingent der Bibliothek erschöpft (3,0 GB von 10,0 GB belegt)");
    // The overload never consults the repository at all - the caller supplies usedBytes itself.
    verifyNoInteractions(documentRepository);
  }
}
