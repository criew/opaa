package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.OrphanedOriginalDeletionResponse;
import io.opaa.api.dto.OrphanedOriginalReportResponse;
import io.opaa.api.types.OrphanedOriginalSkipReason;
import io.opaa.library.OrphanedOriginal;
import io.opaa.library.OrphanedOriginalDeletion;
import io.opaa.library.OrphanedOriginalReport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every field of both responses is filled from the domain result, and no skip reason is lost. */
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
