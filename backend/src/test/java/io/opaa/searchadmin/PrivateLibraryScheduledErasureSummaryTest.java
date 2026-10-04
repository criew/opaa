package io.opaa.searchadmin;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.permission.GroupSizeProperties;
import io.opaa.permission.PersonThreshold;
import io.opaa.searchadmin.PrivateLibrarySummary.ScheduledPart;
import org.junit.jupiter.api.Test;

/**
 * The private libraries due to be erased are a part of all private libraries: told exactly only
 * where both their owners and the owners of the others reach the minimum group size, so neither the
 * part nor its difference to the whole rests on fewer persons.
 */
class PrivateLibraryScheduledErasureSummaryTest {

  private static final PersonThreshold THRESHOLD = new PersonThreshold(new GroupSizeProperties(5));
  private static final LibraryDocumentStats SUMS =
      new LibraryDocumentStats(null, 40, 38, 0, 2, 0, 900, null);

  @Test
  void aPartOfFewOwnersIsOnlyFewerThan() {
    PrivateLibrarySummary summary =
        PrivateLibrarySummary.of(20, 12, SUMS, THRESHOLD, new ScheduledPart(1, 1, 11));

    assertThat(summary.scheduledErasureCount()).isNull();
    assertThat(summary.scheduledErasureCountFewerThan()).isEqualTo(5);
    assertThat(summary.libraryCount()).isEqualTo(20L);
  }

  @Test
  void noneDueLooksLikeAFew() {
    assertThat(PrivateLibrarySummary.of(20, 12, SUMS, THRESHOLD, new ScheduledPart(0, 0, 12)))
        .isEqualTo(PrivateLibrarySummary.of(20, 12, SUMS, THRESHOLD, new ScheduledPart(2, 1, 11)));
  }

  @Test
  void aPartWhoseRestRestsOnFewOwnersIsNotTold() {
    PrivateLibrarySummary summary =
        PrivateLibrarySummary.of(20, 9, SUMS, THRESHOLD, new ScheduledPart(9, 6, 3));

    assertThat(summary.scheduledErasureCount()).isNull();
    assertThat(summary.scheduledErasureCountFewerThan()).isNull();
  }

  @Test
  void aPartAndARestOfEnoughOwnersAreToldExactly() {
    PrivateLibrarySummary summary =
        PrivateLibrarySummary.of(20, 11, SUMS, THRESHOLD, new ScheduledPart(7, 5, 6));

    assertThat(summary.scheduledErasureCount()).isEqualTo(7L);
    assertThat(summary.scheduledErasureCountFewerThan()).isNull();
  }
}
