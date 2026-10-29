package io.opaa.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.knowledge.LibraryStorageQuotaService.IntakeHold;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Unit tests for {@link LibraryStorageQuotaService}. */
class LibraryStorageQuotaServiceTest {

  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final PersonalStorageQuota personalQuota = mock(PersonalStorageQuota.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final SharedLibraryQuotaSettingRepository sharedSettings =
      mock(SharedLibraryQuotaSettingRepository.class);
  private final KnowledgeLibrary library = sharedLibraryOf(UUID.randomUUID());
  private final UUID libraryId = library.getId();

  /** The service under the operator's upper limit {@code capBytes}, without an own value. */
  private LibraryStorageQuotaService service(long capBytes) {
    when(sharedSettings.findSingleton()).thenReturn(Optional.empty());
    return new LibraryStorageQuotaService(
        documentRepository,
        new SharedLibraryQuota(new LibraryProperties(capBytes), sharedSettings),
        personalQuota,
        transactionManager);
  }

  private void ownSharedValue(long bytes) {
    SharedLibraryQuotaSetting setting = mock(SharedLibraryQuotaSetting.class);
    when(setting.getQuotaBytes()).thenReturn(bytes);
    when(sharedSettings.findSingleton()).thenReturn(Optional.of(setting));
  }

  private static KnowledgeLibrary privateLibraryOf(UUID owner) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownerOnly(
            UUID.randomUUID(), "Privat", null, owner, SourceType.UPLOAD, null, null, null);
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
   * The hold of a private library is a transaction that takes the owner's database lock before
   * anything is checked, and commits on close; that it serializes two instances is proved against
   * Postgres in {@code FileSyncPersonalQuotaIntegrationTest}.
   */
  @Test
  void thePrivateIntakeHoldsTheOwnersDatabaseLockForOneTransaction() {
    UUID owner = UUID.randomUUID();
    SimpleTransactionStatus status = new SimpleTransactionStatus();
    when(transactionManager.getTransaction(any())).thenReturn(status);

    IntakeHold hold = service(0).holdIntake(privateLibraryOf(owner));
    hold.close();

    InOrder order = inOrder(transactionManager, documentRepository);
    order
        .verify(transactionManager)
        .getTransaction(
            org.mockito.ArgumentMatchers.argThat(
                definition ->
                    definition.getPropagationBehavior()
                        == TransactionDefinition.PROPAGATION_REQUIRED));
    order.verify(documentRepository).lockPersonalIntake(owner);
    order.verify(transactionManager).commit(status);
  }

  /** A write under the hold that marked the transaction rollback-only is not committed. */
  @Test
  void aHoldWhoseTransactionWasMarkedRollbackOnlyRollsBack() {
    SimpleTransactionStatus status = new SimpleTransactionStatus();
    when(transactionManager.getTransaction(any())).thenReturn(status);

    IntakeHold hold = service(0).holdIntake(privateLibraryOf(UUID.randomUUID()));
    status.setRollbackOnly();
    hold.close();

    verify(transactionManager).rollback(status);
    verify(transactionManager, never()).commit(any());
  }

  @Test
  void aSharedLibraryHoldsNothing() {
    service(0).holdIntake(sharedLibraryOf(UUID.randomUUID())).close();

    verifyNoInteractions(transactionManager, documentRepository);
  }

  @Test
  void zeroOrNegativeQuotaConfigurationMeansUnlimited() {
    // 0/negative is a real "unbegrenzt" configuration, not a
    // silent fallback to the 10 GiB default - important for an existing library already larger
    // than the default, which would otherwise be permanently blocked the moment this quota ships.
    LibraryStorageQuotaService zeroQuotaService = service(0);
    when(documentRepository.sumFileSizeByLibraryId(libraryId)).thenReturn(50L * 1024 * 1024 * 1024);
    assertThat(zeroQuotaService.quotaBytes(library)).isZero();
    assertThat(zeroQuotaService.wouldExceedQuota(library, 1024)).isFalse();

    LibraryStorageQuotaService negativeQuotaService = service(-1);
    assertThat(negativeQuotaService.quotaBytes(library)).isZero();
    assertThat(negativeQuotaService.wouldExceedQuota(library, Long.MAX_VALUE / 2)).isFalse();
  }

  @Test
  void wouldExceedQuotaIsFalseExactlyAtTheLimit() {
    // Grenzfall (#119 acceptance criteria): a document that fills the library exactly up to its
    // quota is accepted, only the first byte past it is rejected.
    LibraryStorageQuotaService quotaService = service(1000);
    when(documentRepository.sumFileSizeByLibraryId(libraryId)).thenReturn(400L);

    assertThat(quotaService.wouldExceedQuota(library, 600)).isFalse();
    assertThat(quotaService.wouldExceedQuota(library, 601)).isTrue();
  }

  @Test
  void quotaExceededMessageNamesUsedAndTotalQuotaAdaptivelyFormatted() {
    // adaptive units in the app-wide form (ByteSizes, the frontend's formatFileSize), so a sub-GB
    // quota never reads as the indistinguishable "0 GB von 0 GB belegt".
    LibraryStorageQuotaService gibQuotaService = service(10L * 1024 * 1024 * 1024);
    when(documentRepository.sumFileSizeByLibraryId(libraryId)).thenReturn(3L * 1024 * 1024 * 1024);
    assertThat(gibQuotaService.quotaExceededMessage(library))
        .isEqualTo("Speicherkontingent der Bibliothek erschöpft (3 GB von 10 GB belegt)");

    LibraryStorageQuotaService smallQuotaService = service(200L * 1024 * 1024);
    when(documentRepository.sumFileSizeByLibraryId(libraryId)).thenReturn(157L * 1024 * 1024 / 2);
    assertThat(smallQuotaService.quotaExceededMessage(library))
        .isEqualTo("Speicherkontingent der Bibliothek erschöpft (78,5 MB von 200 MB belegt)");
  }

  @Test
  void quotaExceededMessageOverloadAvoidsARecomputationWhenTheCallerAlreadyKnowsUsedBytes() {
    LibraryStorageQuotaService quotaService = service(10L * 1024 * 1024 * 1024);

    assertThat(quotaService.quotaExceededMessage(library, 3L * 1024 * 1024 * 1024))
        .isEqualTo("Speicherkontingent der Bibliothek erschöpft (3 GB von 10 GB belegt)");
    // The overload never consults the repository at all - the caller supplies usedBytes itself.
    verifyNoInteractions(documentRepository);
  }

  @Test
  void aSharedLibraryIsHeldToTheHouseWideValueBelowTheUpperLimit() {
    LibraryStorageQuotaService quotaService = service(10_000);
    ownSharedValue(1000);
    when(documentRepository.sumFileSizeByLibraryId(libraryId)).thenReturn(400L);

    assertThat(quotaService.quotaBytes(library)).isEqualTo(1000);
    assertThat(quotaService.verdictFor(library, 600)).isEqualTo(QuotaVerdict.WITHIN);
    assertThat(quotaService.verdictFor(library, 601)).isEqualTo(QuotaVerdict.LIBRARY_EXHAUSTED);
    assertThat(quotaService.quotaExceededMessage(library, 400))
        .isEqualTo("Speicherkontingent der Bibliothek erschöpft (400 B von 1000 B belegt)");
  }

  @Test
  void aPrivateLibraryIsHeldToTheUpperLimitNotToTheHouseWideValue() {
    UUID owner = UUID.randomUUID();
    LibraryStorageQuotaService quotaService = service(10_000);
    ownSharedValue(1000);
    KnowledgeLibrary privateLibrary = privateLibraryOf(owner);
    when(documentRepository.sumFileSizeByLibraryId(privateLibrary.getId())).thenReturn(5000L);
    when(personalQuota.quotaBytes()).thenReturn(0L);

    assertThat(quotaService.quotaBytes(privateLibrary)).isEqualTo(10_000);
    assertThat(quotaService.verdictFor(privateLibrary, 100)).isEqualTo(QuotaVerdict.WITHIN);
  }

  /**
   * A library already above a lowered quota keeps its documents and takes in nothing that grows it;
   * a changed document of the same or a smaller size still replaces its stored version.
   */
  @Test
  void aLibraryAboveItsQuotaTakesNoGrowthButStillAChangeThatDoesNotGrow() {
    LibraryStorageQuotaService quotaService = service(10_000);
    ownSharedValue(1000);
    when(documentRepository.sumFileSizeByLibraryId(libraryId)).thenReturn(1500L);

    assertThat(quotaService.verdictFor(library, 1)).isEqualTo(QuotaVerdict.LIBRARY_EXHAUSTED);
    assertThat(quotaService.wouldExceedQuota(library, 1)).isTrue();
    assertThat(quotaService.verdictFor(library, 0)).isEqualTo(QuotaVerdict.WITHIN);
    assertThat(quotaService.verdictFor(library, -200)).isEqualTo(QuotaVerdict.WITHIN);
  }

  /** The house-wide value is read on every check: a change applies without a restart. */
  @Test
  void aChangedHouseWideValueAppliesToTheNextCheck() {
    LibraryStorageQuotaService quotaService = service(10_000);
    when(documentRepository.sumFileSizeByLibraryId(libraryId)).thenReturn(1500L);
    assertThat(quotaService.verdictFor(library, 100)).isEqualTo(QuotaVerdict.WITHIN);

    ownSharedValue(1000);

    assertThat(quotaService.verdictFor(library, 100)).isEqualTo(QuotaVerdict.LIBRARY_EXHAUSTED);
  }
}
