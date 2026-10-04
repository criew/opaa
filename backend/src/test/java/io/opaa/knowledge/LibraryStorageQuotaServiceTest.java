package io.opaa.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.knowledge.LibraryStorageQuotaService.IntakeHold;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link LibraryStorageQuotaService} (#119, #2166). */
class LibraryStorageQuotaServiceTest {

  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final PersonalStorageQuota personalQuota = mock(PersonalStorageQuota.class);
  private final UUID libraryId = UUID.randomUUID();

  private LibraryStorageQuotaService service(long quotaBytes) {
    LibraryProperties properties = new LibraryProperties(quotaBytes);
    return new LibraryStorageQuotaService(documentRepository, properties, personalQuota);
  }

  private static KnowledgeLibrary privateLibraryOf(UUID owner) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownerOnly(
            UUID.randomUUID(),
            "Privat",
            null,
            owner,
            SourceType.UPLOAD,
            null,
            null,
            null,
            null,
            false);
    return library;
  }

  private static KnowledgeLibrary sharedLibraryOf(UUID owner) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(UUID.randomUUID(), "Geteilt", null, owner);
    return library;
  }

  @Test
  void aSharedLibraryIsHeldToItsOwnQuotaAlone() {
    LibraryStorageQuotaService quotaService = service(1000);
    KnowledgeLibrary shared = sharedLibraryOf(UUID.randomUUID());
    when(documentRepository.sumFileSizeByLibraryId(shared.getId())).thenReturn(400L);

    assertThat(quotaService.verdictFor(shared, 600)).isEqualTo(QuotaVerdict.WITHIN);
    assertThat(quotaService.verdictFor(shared, 601)).isEqualTo(QuotaVerdict.LIBRARY_EXHAUSTED);
    verifyNoInteractions(personalQuota);
  }

  /** The use of all private libraries of the owner counts, not that of the one library. */
  @Test
  void aPrivateLibraryIsHeldToTheQuotaAcrossAllPrivateLibrariesOfItsOwner() {
    UUID owner = UUID.randomUUID();
    LibraryStorageQuotaService quotaService = service(10_000);
    KnowledgeLibrary second = privateLibraryOf(owner);
    when(documentRepository.sumFileSizeByLibraryId(second.getId())).thenReturn(0L);
    when(personalQuota.quotaBytes()).thenReturn(1000L);
    when(personalQuota.usageOf(owner)).thenReturn(900L);

    assertThat(quotaService.verdictFor(second, 100)).isEqualTo(QuotaVerdict.WITHIN);
    assertThat(quotaService.verdictFor(second, 101)).isEqualTo(QuotaVerdict.PERSON_EXHAUSTED);
  }

  @Test
  void theQuotaOfTheLibraryIsAskedFirst() {
    UUID owner = UUID.randomUUID();
    LibraryStorageQuotaService quotaService = service(500);
    KnowledgeLibrary library = privateLibraryOf(owner);
    when(documentRepository.sumFileSizeByLibraryId(library.getId())).thenReturn(450L);
    when(personalQuota.quotaBytes()).thenReturn(1000L);
    when(personalQuota.usageOf(owner)).thenReturn(990L);

    assertThat(quotaService.verdictFor(library, 100)).isEqualTo(QuotaVerdict.LIBRARY_EXHAUSTED);
  }

  @Test
  void aPersonalQuotaOfZeroIsUnlimited() {
    UUID owner = UUID.randomUUID();
    LibraryStorageQuotaService quotaService = service(0);
    KnowledgeLibrary library = privateLibraryOf(owner);
    when(personalQuota.quotaBytes()).thenReturn(0L);
    when(personalQuota.usageOf(owner)).thenReturn(Long.MAX_VALUE / 2);

    assertThat(quotaService.verdictFor(library, 1024)).isEqualTo(QuotaVerdict.WITHIN);
  }

  @Test
  void thePersonalMessageNamesTheOwnersUseAndTheLimit() {
    UUID owner = UUID.randomUUID();
    LibraryStorageQuotaService quotaService = service(0);
    when(personalQuota.quotaBytes()).thenReturn(10L * 1024 * 1024 * 1024);
    when(personalQuota.usageOf(owner)).thenReturn(9L * 1024 * 1024 * 1024);

    assertThat(quotaService.personalQuotaExceededMessage(privateLibraryOf(owner)))
        .isEqualTo(
            "Speicherkontingent Ihrer privaten Bibliotheken erschöpft (9 GB von 10 GB belegt)");
  }

  /**
   * Two private libraries of one owner take turns; another owner and a shared library do not wait.
   */
  @Test
  void theIntakeOfOneOwnersPrivateLibrariesIsSerialized() throws Exception {
    UUID owner = UUID.randomUUID();
    LibraryStorageQuotaService quotaService = service(0);
    KnowledgeLibrary first = privateLibraryOf(owner);
    KnowledgeLibrary second = privateLibraryOf(owner);

    IntakeHold held = quotaService.holdIntake(first);
    CompletableFuture<Void> sameOwner =
        CompletableFuture.runAsync(() -> quotaService.holdIntake(second).close());
    CompletableFuture.runAsync(() -> quotaService.holdIntake(sharedLibraryOf(owner)).close())
        .get(5, TimeUnit.SECONDS);
    UUID other = otherOwnerOnAnotherStripe(owner);
    CompletableFuture.runAsync(() -> quotaService.holdIntake(privateLibraryOf(other)).close())
        .get(5, TimeUnit.SECONDS);

    try {
      sameOwner.get(200, TimeUnit.MILLISECONDS);
      throw new AssertionError("the second library of the same owner did not wait");
    } catch (TimeoutException expected) {
      // still waiting for the first hold
    }
    held.close();
    sameOwner.get(5, TimeUnit.SECONDS);
  }

  private static UUID otherOwnerOnAnotherStripe(UUID owner) {
    UUID other = UUID.randomUUID();
    while (Math.floorMod(other.hashCode(), 64) == Math.floorMod(owner.hashCode(), 64)) {
      other = UUID.randomUUID();
    }
    return other;
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
    // adaptive units in the app-wide form (ByteSizes, the frontend's formatFileSize), so a sub-GB
    // quota never reads as the indistinguishable "0 GB von 0 GB belegt".
    LibraryStorageQuotaService gibQuotaService = service(10L * 1024 * 1024 * 1024);
    when(documentRepository.sumFileSizeByLibraryId(libraryId)).thenReturn(3L * 1024 * 1024 * 1024);
    assertThat(gibQuotaService.quotaExceededMessage(libraryId))
        .isEqualTo("Speicherkontingent der Bibliothek erschöpft (3 GB von 10 GB belegt)");

    LibraryStorageQuotaService smallQuotaService = service(200L * 1024 * 1024);
    when(documentRepository.sumFileSizeByLibraryId(libraryId)).thenReturn(157L * 1024 * 1024 / 2);
    assertThat(smallQuotaService.quotaExceededMessage(libraryId))
        .isEqualTo("Speicherkontingent der Bibliothek erschöpft (78,5 MB von 200 MB belegt)");
  }

  @Test
  void quotaExceededMessageOverloadAvoidsARecomputationWhenTheCallerAlreadyKnowsUsedBytes() {
    LibraryStorageQuotaService quotaService = service(10L * 1024 * 1024 * 1024);

    assertThat(quotaService.quotaExceededMessage(libraryId, 3L * 1024 * 1024 * 1024))
        .isEqualTo("Speicherkontingent der Bibliothek erschöpft (3 GB von 10 GB belegt)");
    // The overload never consults the repository at all - the caller supplies usedBytes itself.
    verifyNoInteractions(documentRepository);
  }
}
