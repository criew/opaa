package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.opaa.common.NotFoundException;
import io.opaa.indexing.document.AttachmentFilePath;
import io.opaa.indexing.document.DocumentRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two steps of the orphan cleanup over the filesystem adapter (ADR-0030, "Konsequenzen"):
 * reporting names what no row points to and is old enough, changes nothing, and is capped; deleting
 * removes only what it is handed and re-checks each locator. Row lookups are mocked - the rule
 * under test is "orphaned is what no row points to", whatever the row.
 */
class OrphanedOriginalCleanupServiceTest {

  private static final int GRACE_MINUTES = 60;

  @TempDir Path storageDir;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID libraryId = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-09-11T12:00:00Z");
  private final KnowledgeLibraryRepository libraryRepository =
      mock(KnowledgeLibraryRepository.class);
  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final List<String> rows = new ArrayList<>();
  private FilesystemUploadedOriginalStore store;
  private OrphanedOriginalCleanupService service;

  @BeforeEach
  void setUp() {
    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    when(library.getOrganizationId()).thenReturn(organizationId);
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    when(documentRepository.findFilePathsByLibraryId(libraryId)).thenReturn(rows);
    store =
        spy(
            new FilesystemUploadedOriginalStore(
                new UploadProperties(storageDir.toString(), null, 1024L, null, 0, GRACE_MINUTES)));
    service = service(store);
  }

  private OrphanedOriginalCleanupService service(UploadedOriginalStore withStore) {
    return new OrphanedOriginalCleanupService(
        libraryRepository,
        documentRepository,
        withStore,
        new UploadProperties(storageDir.toString(), null, 1024L, null, 0, GRACE_MINUTES),
        Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  void anOriginalNoRowPointsToIsReportedAndLeftInPlace() throws IOException {
    String orphan = stored("verwaist", now.minus(Duration.ofHours(2)));
    String owned = stored("gehört einer Zeile", now.minus(Duration.ofHours(2)));
    rows.add(owned);

    OrphanedOriginalReport report = service.report(organizationId, libraryId, null);

    assertThat(report.orphans()).extracting(OrphanedOriginal::locator).containsExactly(orphan);
    assertThat(report.orphans().get(0).size()).isEqualTo("verwaist".length());
    assertThat(report.orphanCount()).isEqualTo(1);
    assertThat(report.scannedCount()).isEqualTo(2);
    assertThat(report.withinGracePeriodCount()).isZero();
    assertThat(report.minimumAgeMinutes()).isEqualTo(GRACE_MINUTES);
    assertThat(report.isTruncated()).isFalse();
    assertThat(Path.of(orphan)).as("reporting never deletes").exists();
  }

  @Test
  void anOriginalInsideTheGracePeriodIsNeverReported() throws IOException {
    String fresh = stored("gerade hochgeladen", now.minus(Duration.ofMinutes(GRACE_MINUTES - 1)));
    String atTheEdge =
        stored("genau an der Schwelle", now.minus(Duration.ofMinutes(GRACE_MINUTES)));

    OrphanedOriginalReport report = service.report(organizationId, libraryId, null);

    assertThat(report.orphans()).extracting(OrphanedOriginal::locator).containsExactly(atTheEdge);
    assertThat(report.withinGracePeriodCount()).isEqualTo(1);
    assertThat(report.scannedCount()).isEqualTo(2);
    assertThat(Path.of(fresh)).exists();
  }

  @Test
  void anOriginalARowPointsToIsNotReportedWhateverTheRowIs() throws IOException {
    // Every file_path of the library counts - a parent's locator and its attachment's synthetic
    // path alike (ADR-0022): the synthetic one can never match a stored original, and leaving
    // it out would be an assumption about the future in the direction that deletes more.
    String parent = stored("die Mail", now.minus(Duration.ofDays(3)));
    rows.add(parent);
    rows.add(AttachmentFilePath.of(parent, 0, "anlage.pdf"));

    OrphanedOriginalReport report = service.report(organizationId, libraryId, null);

    assertThat(report.orphans()).isEmpty();
    assertThat(report.orphanCount()).isZero();
    assertThat(report.scannedCount()).isEqualTo(1);
  }

  @Test
  void theListingIsCappedButEveryOrphanIsCounted() throws IOException {
    for (int i = 0; i < OrphanedOriginalCleanupService.MAX_LISTED + 1; i++) {
      stored("o" + i, now.minus(Duration.ofDays(1)));
    }

    OrphanedOriginalReport report = service.report(organizationId, libraryId, null);

    assertThat(report.orphans()).hasSize(OrphanedOriginalCleanupService.MAX_LISTED);
    assertThat(report.orphanCount()).isEqualTo(OrphanedOriginalCleanupService.MAX_LISTED + 1);
    assertThat(report.isTruncated()).isTrue();
  }

  @Test
  void theMinimumAgeMayRaiseTheGracePeriodButNeverLowerIt() throws IOException {
    String twoHoursOld = stored("zwei Stunden", now.minus(Duration.ofHours(2)));
    String twoDaysOld = stored("zwei Tage", now.minus(Duration.ofDays(2)));

    OrphanedOriginalReport raised = service.report(organizationId, libraryId, 24 * 60);
    assertThat(raised.orphans()).extracting(OrphanedOriginal::locator).containsExactly(twoDaysOld);
    assertThat(raised.withinGracePeriodCount()).isEqualTo(1);
    assertThat(raised.minimumAgeMinutes()).isEqualTo(24 * 60);

    assertThatThrownBy(() -> service.report(organizationId, libraryId, GRACE_MINUTES - 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Schonfrist")
        .hasMessageContaining(String.valueOf(GRACE_MINUTES));
    assertThat(Path.of(twoHoursOld)).exists();
  }

  @Test
  void aLibraryOfAnotherOrganizationIsAbsentForBothSteps() {
    assertThatThrownBy(() -> service.report(UUID.randomUUID(), libraryId, null))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.delete(UUID.randomUUID(), libraryId, List.of("x")))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.report(organizationId, UUID.randomUUID(), null))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void deletingRemovesOnlyTheNamedLocatorsAndRechecksEachOfThem() throws IOException {
    String orphan = stored("verwaist", now.minus(Duration.ofHours(2)));
    String unnamedOrphan =
        stored("auch verwaist, aber nicht genannt", now.minus(Duration.ofHours(2)));
    String referencedSince = stored("inzwischen eine Zeile", now.minus(Duration.ofHours(2)));
    rows.add(referencedSince);
    String fresh = stored("zu jung", now.minus(Duration.ofMinutes(5)));
    String gone = storageDir.resolve(libraryId.toString()).resolve("weg.pdf").toString();
    String foreign =
        storageDir.resolve(UUID.randomUUID().toString()).resolve("fremd.pdf").toString();

    OrphanedOriginalDeletion deletion =
        service.delete(
            organizationId,
            libraryId,
            List.of(orphan, referencedSince, fresh, gone, foreign, orphan, " "));

    assertThat(deletion.deleted()).containsExactly(orphan);
    assertThat(deletion.skipped())
        .containsExactly(
            new OrphanedOriginalDeletion.Skipped(
                referencedSince, OrphanedOriginalSkipReason.REFERENCED),
            new OrphanedOriginalDeletion.Skipped(
                fresh, OrphanedOriginalSkipReason.WITHIN_GRACE_PERIOD),
            new OrphanedOriginalDeletion.Skipped(gone, OrphanedOriginalSkipReason.NOT_IN_STORE),
            new OrphanedOriginalDeletion.Skipped(foreign, OrphanedOriginalSkipReason.NOT_IN_STORE));
    assertThat(Path.of(orphan)).doesNotExist();
    assertThat(Path.of(unnamedOrphan)).as("what was not named is not touched").exists();
    assertThat(Path.of(referencedSince)).exists();
    assertThat(Path.of(fresh)).exists();
  }

  @Test
  void deletingRejectsAnEmptyListAndOneBeyondTheCap() {
    assertThatThrownBy(() -> service.delete(organizationId, libraryId, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("leer");
    assertThatThrownBy(() -> service.delete(organizationId, libraryId, List.of(" ", "")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("leer");
    List<String> tooMany = new ArrayList<>();
    for (int i = 0; i <= OrphanedOriginalCleanupService.MAX_LISTED; i++) {
      tooMany.add("locator-" + i);
    }
    assertThatThrownBy(() -> service.delete(organizationId, libraryId, tooMany))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(String.valueOf(OrphanedOriginalCleanupService.MAX_LISTED));
  }

  @Test
  void aRemovalTheStoreRefusedIsReportedAsFailedNotAsDone() throws IOException {
    String orphan = stored("bleibt hängen", now.minus(Duration.ofHours(2)));
    doNothing().when(store).delete(any());

    OrphanedOriginalDeletion deletion = service.delete(organizationId, libraryId, List.of(orphan));

    assertThat(deletion.deleted()).isEmpty();
    assertThat(deletion.skipped())
        .containsExactly(
            new OrphanedOriginalDeletion.Skipped(orphan, OrphanedOriginalSkipReason.DELETE_FAILED));
    assertThat(Path.of(orphan)).exists();
  }

  @Test
  void aLocatorARowPointsToIsReportedAsReferencedEvenWhenTheStoreNoLongerHoldsIt() {
    // Pins the order of the re-checks: "a row points to this" outranks "the store does not hold
    // it". The caller who pasted a stale list must see that a row owns the locator, not the
    // harmless "nothing to do here".
    String referencedButGone =
        storageDir.resolve(libraryId.toString()).resolve("zeile-ohne-objekt.pdf").toString();
    rows.add(referencedButGone);

    OrphanedOriginalDeletion deletion =
        service.delete(organizationId, libraryId, List.of(referencedButGone));

    assertThat(deletion.deleted()).isEmpty();
    assertThat(deletion.skipped())
        .containsExactly(
            new OrphanedOriginalDeletion.Skipped(
                referencedButGone, OrphanedOriginalSkipReason.REFERENCED));
  }

  /** A stored original of {@code libraryId} whose file dates from {@code lastModified}. */
  private String stored(String content, Instant lastModified) throws IOException {
    UploadedOriginalRef ref =
        store
            .accept(
                libraryId,
                ".pdf",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)))
            .store();
    Files.setLastModifiedTime(Path.of(ref.locator()), FileTime.from(lastModified));
    return ref.locator();
  }
}
