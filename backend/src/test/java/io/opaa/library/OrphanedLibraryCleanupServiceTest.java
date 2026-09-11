package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.OrphanedOriginalSkipReason;
import io.opaa.common.NotFoundException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The storage-bound run over the filesystem adapter: the storage areas whose library row is gone,
 * which the library-bound run can never reach. What decides is the library row's existence -
 * anywhere, not only in the caller's organization - and the organization segment of the storage
 * area itself. Row lookups are mocked; the rows themselves are covered by {@code
 * OrphanedOriginalCleanupIntegrationTest}.
 */
class OrphanedLibraryCleanupServiceTest {

  private static final int GRACE_MINUTES = 60;

  @TempDir Path storageDir;

  private final UUID organizationId = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-09-11T12:00:00Z");
  private final KnowledgeLibraryRepository libraryRepository =
      mock(KnowledgeLibraryRepository.class);
  private final DocumentRepository documentRepository = mock(DocumentRepository.class);

  /** Every library row that exists, and the organization it belongs to. */
  private final Map<UUID, UUID> libraryRows = new HashMap<>();

  private FilesystemUploadedOriginalStore store;
  private OrphanedOriginalCleanupService service;

  @BeforeEach
  void setUp() {
    when(libraryRepository.findIdsByOrganizationId(any()))
        .thenAnswer(
            invocation ->
                libraryRows.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(invocation.getArgument(0)))
                    .map(Map.Entry::getKey)
                    .toList());
    when(libraryRepository.existsById(any()))
        .thenAnswer(invocation -> libraryRows.containsKey(invocation.getArgument(0)));
    UploadProperties uploadProperties =
        new UploadProperties(storageDir.toString(), null, 1024L, null, 0, GRACE_MINUTES);
    store = new FilesystemUploadedOriginalStore(uploadProperties);
    service =
        new OrphanedOriginalCleanupService(
            libraryRepository,
            documentRepository,
            store,
            uploadProperties,
            Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  void anOriginalOfADeletedLibraryIsFoundAndCanBeRemoved() throws IOException {
    UUID deletedLibrary = UUID.randomUUID();
    String orphan = stored(deletedLibrary, "verwaist", now.minus(Duration.ofHours(2)));
    String secondOrphan = stored(deletedLibrary, "auch verwaist", now.minus(Duration.ofHours(2)));

    // The gap this run closes: without the library row the library-bound run cannot even name
    // the area, so these bytes were reachable by no call at all.
    assertThatThrownBy(() -> service.report(organizationId, deletedLibrary, null))
        .isInstanceOf(NotFoundException.class);

    OrphanedLibraryReport report = service.reportOrphanedLibraries(organizationId, null);

    assertThat(report.libraries()).hasSize(1);
    OrphanedLibrary orphaned = report.libraries().get(0);
    assertThat(orphaned.libraryId()).isEqualTo(deletedLibrary);
    assertThat(orphaned.orphans())
        .extracting(OrphanedOriginal::locator)
        .containsExactlyInAnyOrder(orphan, secondOrphan);
    assertThat(orphaned.orphanCount()).isEqualTo(2);
    assertThat(orphaned.scannedCount()).isEqualTo(2);
    assertThat(orphaned.withinGracePeriodCount()).isZero();
    assertThat(orphaned.totalSize()).isEqualTo("verwaist".length() + "auch verwaist".length());
    assertThat(orphaned.isTruncated()).isFalse();
    assertThat(report.libraryCount()).isEqualTo(1);
    assertThat(report.scannedLibraryCount()).isEqualTo(1);
    assertThat(report.knownLibraryCount()).isZero();
    assertThat(report.minimumAgeMinutes()).isEqualTo(GRACE_MINUTES);
    assertThat(report.isTruncated()).isFalse();
    assertThat(Path.of(orphan)).as("reporting never deletes").exists();

    OrphanedOriginalDeletion deletion =
        service.deleteInOrphanedLibrary(organizationId, deletedLibrary, List.of(orphan));

    assertThat(deletion.deleted()).containsExactly(orphan);
    assertThat(deletion.skipped()).isEmpty();
    assertThat(Path.of(orphan)).doesNotExist();
    assertThat(Path.of(secondOrphan)).as("what was not named stays").exists();
  }

  @Test
  void aStorageAreaWithALibraryRowIsLeftAloneEvenWhenTheRowBelongsElsewhere() throws IOException {
    UUID ownLibrary = UUID.randomUUID();
    libraryRows.put(ownLibrary, organizationId);
    String owned = stored(ownLibrary, "gehört einer Bibliothek", now.minus(Duration.ofHours(2)));
    // A row of another organization protects the area just as well: a misfiled area is not this
    // caller's to remove, and the acceptance rule is "a row exists", not "a row of mine exists".
    UUID foreignLibrary = UUID.randomUUID();
    libraryRows.put(foreignLibrary, UUID.randomUUID());
    String misfiled = stored(foreignLibrary, "fremde Zeile", now.minus(Duration.ofHours(2)));

    OrphanedLibraryReport report = service.reportOrphanedLibraries(organizationId, null);

    assertThat(report.libraries()).isEmpty();
    assertThat(report.libraryCount()).isZero();
    assertThat(report.scannedLibraryCount()).isEqualTo(2);
    assertThat(report.knownLibraryCount()).isEqualTo(2);

    // The same rejection for both, naming neither library: a caller must not learn from it that
    // an id they guessed belongs to a library of an organization they cannot see.
    String rejection =
        "Diese Bibliothek lässt sich über diesen Weg nicht aufräumen - für eine vorhandene"
            + " Bibliothek ist der bibliotheksbezogene Aufräumlauf zuständig";
    assertThatThrownBy(
            () ->
                service.deleteInOrphanedLibrary(organizationId, foreignLibrary, List.of(misfiled)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(rejection);
    assertThatThrownBy(
            () -> service.deleteInOrphanedLibrary(organizationId, ownLibrary, List.of(owned)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(rejection);
    assertThat(Path.of(misfiled)).exists();
    assertThat(Path.of(owned)).exists();
  }

  @Test
  void theCallersOwnOrganizationIsTheOnlyOneWalkedAndTheOnlyOneDeletableFrom() throws IOException {
    UUID otherOrganization = UUID.randomUUID();
    UUID otherOrganizationsLibrary = UUID.randomUUID();
    String foreignOrphan =
        stored(
            otherOrganization,
            otherOrganizationsLibrary,
            "verwaist, aber nicht meins",
            now.minus(Duration.ofHours(2)));
    UUID ownDeletedLibrary = UUID.randomUUID();
    stored(ownDeletedLibrary, "verwaist und meins", now.minus(Duration.ofHours(2)));

    OrphanedLibraryReport report = service.reportOrphanedLibraries(organizationId, null);

    assertThat(report.libraries())
        .extracting(OrphanedLibrary::libraryId)
        .containsExactly(ownDeletedLibrary);
    assertThat(report.scannedLibraryCount()).isEqualTo(1);

    // Naming the foreign locator under an own-looking library does not reach it either: the
    // store resolves every locator against exactly the named organization and library.
    OrphanedOriginalDeletion deletion =
        service.deleteInOrphanedLibrary(
            organizationId, otherOrganizationsLibrary, List.of(foreignOrphan));

    assertThat(deletion.deleted()).isEmpty();
    assertThat(deletion.skipped())
        .containsExactly(
            new OrphanedOriginalDeletion.Skipped(
                foreignOrphan, OrphanedOriginalSkipReason.NOT_IN_STORE));
    assertThat(Path.of(foreignOrphan)).exists();
  }

  @Test
  void aFreshlyWrittenOriginalIsCountedButNotOfferedUntilTheGracePeriodIsOver() throws IOException {
    UUID deletedLibrary = UUID.randomUUID();
    String fresh =
        stored(
            deletedLibrary, "gerade hochgeladen", now.minus(Duration.ofMinutes(GRACE_MINUTES - 1)));

    OrphanedLibraryReport report = service.reportOrphanedLibraries(organizationId, null);

    assertThat(report.libraries()).hasSize(1);
    OrphanedLibrary orphaned = report.libraries().get(0);
    assertThat(orphaned.orphans()).isEmpty();
    assertThat(orphaned.orphanCount()).isZero();
    assertThat(orphaned.scannedCount()).isEqualTo(1);
    assertThat(orphaned.withinGracePeriodCount()).isEqualTo(1);
    assertThat(orphaned.totalSize()).isZero();
    assertThat(report.libraryCount()).isEqualTo(1);

    OrphanedOriginalDeletion deletion =
        service.deleteInOrphanedLibrary(organizationId, deletedLibrary, List.of(fresh));

    assertThat(deletion.deleted()).isEmpty();
    assertThat(deletion.skipped())
        .containsExactly(
            new OrphanedOriginalDeletion.Skipped(
                fresh, OrphanedOriginalSkipReason.WITHIN_GRACE_PERIOD));
    assertThat(Path.of(fresh)).exists();
  }

  @Test
  void anOldOriginalOfAJustDeletedLibraryIsOfferedRightAway() throws IOException {
    // The grace period runs from the original's own last write, not from the deletion of its
    // library: deleting a library opens no window in which its originals could be recovered.
    UUID justDeleted = UUID.randomUUID();
    String longStored = stored(justDeleted, "im Juni abgelegt", now.minus(Duration.ofDays(100)));

    OrphanedLibraryReport report = service.reportOrphanedLibraries(organizationId, null);

    OrphanedLibrary orphaned = report.libraries().get(0);
    assertThat(orphaned.orphans())
        .extracting(OrphanedOriginal::locator)
        .containsExactly(longStored);
    assertThat(orphaned.withinGracePeriodCount()).isZero();

    OrphanedOriginalDeletion deletion =
        service.deleteInOrphanedLibrary(organizationId, justDeleted, List.of(longStored));

    assertThat(deletion.deleted()).containsExactly(longStored);
    assertThat(Path.of(longStored)).doesNotExist();
  }

  @Test
  void aStorageAreaHoldingNoOriginalAtAllIsScannedButNotReported() throws IOException {
    // An emptied directory is nothing this run could remove - it deletes originals, not folders -
    // so reporting it would put an entry into every future report that never goes away.
    Files.createDirectories(
        storageDir.resolve(organizationId.toString()).resolve(UUID.randomUUID().toString()));

    OrphanedLibraryReport report = service.reportOrphanedLibraries(organizationId, null);

    assertThat(report.libraries()).isEmpty();
    assertThat(report.libraryCount()).isZero();
    assertThat(report.knownLibraryCount()).isZero();
    assertThat(report.scannedLibraryCount()).isEqualTo(1);
    assertThat(report.isTruncated()).isFalse();
  }

  @Test
  void theMinimumAgeMayRaiseTheGracePeriodButNeverLowerIt() throws IOException {
    UUID deletedLibrary = UUID.randomUUID();
    stored(deletedLibrary, "zwei Stunden", now.minus(Duration.ofHours(2)));
    String twoDaysOld = stored(deletedLibrary, "zwei Tage", now.minus(Duration.ofDays(2)));

    OrphanedLibraryReport raised = service.reportOrphanedLibraries(organizationId, 24 * 60);

    assertThat(raised.libraries().get(0).orphans())
        .extracting(OrphanedOriginal::locator)
        .containsExactly(twoDaysOld);
    assertThat(raised.libraries().get(0).withinGracePeriodCount()).isEqualTo(1);
    assertThat(raised.minimumAgeMinutes()).isEqualTo(24 * 60);

    assertThatThrownBy(() -> service.reportOrphanedLibraries(organizationId, GRACE_MINUTES - 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Schonfrist");
  }

  @Test
  void theListingIsCappedOverStorageAreasAndEveryAreaIsStillCounted() throws IOException {
    for (int i = 0; i <= OrphanedOriginalCleanupService.MAX_LISTED; i++) {
      stored(UUID.randomUUID(), "o" + i, now.minus(Duration.ofDays(1)));
    }

    OrphanedLibraryReport report = service.reportOrphanedLibraries(organizationId, null);

    assertThat(report.libraries()).hasSize(OrphanedOriginalCleanupService.MAX_LISTED);
    assertThat(report.libraryCount()).isEqualTo(OrphanedOriginalCleanupService.MAX_LISTED + 1);
    assertThat(report.scannedLibraryCount())
        .isEqualTo(OrphanedOriginalCleanupService.MAX_LISTED + 1);
    assertThat(report.isTruncated()).isTrue();
  }

  @Test
  void theListingIsCappedOverTheOrphansOfAllAreasTogether() throws IOException {
    // The cap is one budget across the whole report, not one per area: two areas of 251 orphans
    // must not hand out 502 locators.
    int perLibrary = OrphanedOriginalCleanupService.MAX_LISTED / 2 + 1;
    for (int library = 0; library < 2; library++) {
      UUID deletedLibrary = UUID.randomUUID();
      for (int i = 0; i < perLibrary; i++) {
        stored(deletedLibrary, "o" + library + "-" + i, now.minus(Duration.ofDays(1)));
      }
    }

    OrphanedLibraryReport report = service.reportOrphanedLibraries(organizationId, null);

    assertThat(report.libraries()).hasSize(2);
    assertThat(report.libraries().stream().mapToInt(library -> library.orphans().size()).sum())
        .isEqualTo(OrphanedOriginalCleanupService.MAX_LISTED);
    assertThat(report.libraries().stream().mapToInt(OrphanedLibrary::orphanCount).sum())
        .isEqualTo(2 * perLibrary);
    assertThat(report.libraryCount()).isEqualTo(2);
    assertThat(report.isTruncated()).isTrue();
  }

  @Test
  void deletingRejectsAnEmptyListAndOneBeyondTheCap() {
    UUID deletedLibrary = UUID.randomUUID();

    assertThatThrownBy(
            () -> service.deleteInOrphanedLibrary(organizationId, deletedLibrary, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("leer");
    List<String> tooMany =
        java.util.stream.IntStream.rangeClosed(0, OrphanedOriginalCleanupService.MAX_LISTED)
            .mapToObj(i -> "locator-" + i)
            .toList();
    assertThatThrownBy(
            () -> service.deleteInOrphanedLibrary(organizationId, deletedLibrary, tooMany))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(String.valueOf(OrphanedOriginalCleanupService.MAX_LISTED));
  }

  private String stored(UUID libraryId, String content, Instant lastModified) throws IOException {
    return stored(organizationId, libraryId, content, lastModified);
  }

  /** A stored original whose file dates from {@code lastModified}. */
  private String stored(UUID organization, UUID libraryId, String content, Instant lastModified)
      throws IOException {
    UploadedOriginalRef ref =
        store
            .accept(
                organization,
                libraryId,
                ".pdf",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)))
            .store();
    Files.setLastModifiedTime(Path.of(ref.locator()), FileTime.from(lastModified));
    return ref.locator();
  }
}
