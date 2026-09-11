package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.OrphanedLibraryReportResponse;
import io.opaa.api.dto.OrphanedLibraryResponse;
import io.opaa.api.dto.OrphanedOriginalDeletionResponse;
import io.opaa.api.dto.OrphanedOriginalReportResponse;
import io.opaa.api.types.OrphanedOriginalSkipReason;
import io.opaa.library.OrphanedLibrary;
import io.opaa.library.OrphanedLibraryReport;
import io.opaa.library.OrphanedOriginal;
import io.opaa.library.OrphanedOriginalDeletion;
import io.opaa.library.OrphanedOriginalReport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every field of every response is filled from the domain result, and no skip reason is lost. */
class OrphanedOriginalResponseMapperTest {

  @Test
  void theReportCarriesEveryOrphanAndEveryCount() {
    OrphanedOriginalReport report =
        new OrphanedOriginalReport(
            List.of(
                new OrphanedOriginal(
                    "s3://bucket/uploads/lib/eins.pdf",
                    Instant.parse("2026-09-01T10:00:00Z"),
                    4711L)),
            7,
            120,
            113,
            5,
            180);

    OrphanedOriginalReportResponse response =
        OrphanedOriginalResponseMapper.toReportResponse(report);

    assertThat(response.getOrphans()).hasSize(1);
    assertThat(response.getOrphans().get(0).getLocator())
        .isEqualTo("s3://bucket/uploads/lib/eins.pdf");
    assertThat(response.getOrphans().get(0).getLastModified())
        .isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
    assertThat(response.getOrphans().get(0).getSize()).isEqualTo(4711L);
    assertThat(response.getOrphanCount()).isEqualTo(7);
    assertThat(response.getScannedCount()).isEqualTo(120);
    assertThat(response.getReferencedCount()).isEqualTo(113);
    assertThat(response.getWithinGracePeriodCount()).isEqualTo(5);
    assertThat(response.getMinimumAgeMinutes()).isEqualTo(180);
    assertThat(response.getTruncated()).isTrue();
  }

  @Test
  void aCompleteReportIsNotTruncated() {
    OrphanedOriginalReportResponse response =
        OrphanedOriginalResponseMapper.toReportResponse(
            new OrphanedOriginalReport(List.of(), 0, 3, 3, 0, 60));

    assertThat(response.getOrphans()).isEmpty();
    assertThat(response.getTruncated()).isFalse();
  }

  @Test
  void theLibraryReportCarriesEveryAreaEveryOrphanAndEveryCount() {
    UUID deletedLibrary = UUID.randomUUID();
    OrphanedLibraryReport report =
        new OrphanedLibraryReport(
            List.of(
                new OrphanedLibrary(
                    deletedLibrary,
                    List.of(
                        new OrphanedOriginal(
                            "s3://bucket/uploads/org/lib/eins.pdf",
                            Instant.parse("2026-09-01T10:00:00Z"),
                            4711L)),
                    9,
                    11,
                    2,
                    99999L)),
            4,
            17,
            13,
            180);

    OrphanedLibraryReportResponse response =
        OrphanedOriginalResponseMapper.toLibraryReportResponse(report);

    assertThat(response.getLibraries()).hasSize(1);
    OrphanedLibraryResponse library = response.getLibraries().get(0);
    assertThat(library.getLibraryId()).isEqualTo(deletedLibrary);
    assertThat(library.getOrphans()).hasSize(1);
    assertThat(library.getOrphans().get(0).getLocator())
        .isEqualTo("s3://bucket/uploads/org/lib/eins.pdf");
    assertThat(library.getOrphans().get(0).getLastModified())
        .isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
    assertThat(library.getOrphans().get(0).getSize()).isEqualTo(4711L);
    assertThat(library.getOrphanCount()).isEqualTo(9);
    assertThat(library.getScannedCount()).isEqualTo(11);
    assertThat(library.getWithinGracePeriodCount()).isEqualTo(2);
    assertThat(library.getTotalSize()).isEqualTo(99999L);
    assertThat(library.getTruncated()).isTrue();
    assertThat(response.getLibraryCount()).isEqualTo(4);
    assertThat(response.getScannedLibraryCount()).isEqualTo(17);
    assertThat(response.getKnownLibraryCount()).isEqualTo(13);
    assertThat(response.getMinimumAgeMinutes()).isEqualTo(180);
    assertThat(response.getTruncated()).isTrue();
  }

  @Test
  void aCompleteLibraryReportIsNotTruncated() {
    OrphanedLibraryReportResponse response =
        OrphanedOriginalResponseMapper.toLibraryReportResponse(
            new OrphanedLibraryReport(
                List.of(
                    new OrphanedLibrary(
                        UUID.randomUUID(),
                        List.of(
                            new OrphanedOriginal(
                                "s3://bucket/uploads/org/lib/eins.pdf",
                                Instant.parse("2026-09-01T10:00:00Z"),
                                12L)),
                        1,
                        1,
                        0,
                        12L)),
                1,
                3,
                2,
                60));

    assertThat(response.getLibraries().get(0).getTruncated()).isFalse();
    assertThat(response.getTruncated()).isFalse();
  }

  @Test
  void everySkippedLocatorReachesTheResponseWithItsOwnReason() {
    List<OrphanedOriginalDeletion.Skipped> skipped = new ArrayList<>();
    for (OrphanedOriginalSkipReason reason : OrphanedOriginalSkipReason.values()) {
      skipped.add(new OrphanedOriginalDeletion.Skipped("locator-" + reason, reason));
    }

    OrphanedOriginalDeletionResponse response =
        OrphanedOriginalResponseMapper.toDeletionResponse(
            new OrphanedOriginalDeletion(List.of("weg.pdf"), skipped));

    assertThat(response.getDeleted()).containsExactly("weg.pdf");
    assertThat(response.getSkipped())
        .hasSize(OrphanedOriginalSkipReason.values().length)
        .allSatisfy(
            entry -> assertThat(entry.getLocator()).isEqualTo("locator-" + entry.getReason()));
  }
}
