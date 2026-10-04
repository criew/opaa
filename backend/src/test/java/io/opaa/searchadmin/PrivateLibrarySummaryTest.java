package io.opaa.searchadmin;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.permission.GroupSizeProperties;
import io.opaa.permission.PersonThreshold;
import org.junit.jupiter.api.Test;

/**
 * The private libraries' line of the index status rests on their owners: below the minimum group
 * size of owners - zero included - only "fewer than" and no sums, however many libraries they keep.
 */
class PrivateLibrarySummaryTest {

  private static final PersonThreshold THRESHOLD = new PersonThreshold(new GroupSizeProperties(5));
  private static final LibraryDocumentStats SUMS =
      new LibraryDocumentStats(null, 40, 38, 0, 2, 0, 900, null);

  @Test
  void noPrivateLibraryLooksLikeAFew() {
    assertThat(PrivateLibrarySummary.of(0, 0, LibraryDocumentStats.empty(null), THRESHOLD))
        .isEqualTo(PrivateLibrarySummary.of(3, 2, SUMS, THRESHOLD))
        .isEqualTo(new PrivateLibrarySummary(null, 5, null, null, null));
  }

  @Test
  void manyLibrariesOfFewOwnersStayMasked() {
    assertThat(PrivateLibrarySummary.of(12, 4, SUMS, THRESHOLD))
        .isEqualTo(new PrivateLibrarySummary(null, 5, null, null, null));
  }

  @Test
  void enoughOwnersAreToldExactlyWithTheSums() {
    assertThat(PrivateLibrarySummary.of(7, 5, SUMS, THRESHOLD))
        .isEqualTo(new PrivateLibrarySummary(7L, null, 40L, 2L, 900L));
  }
}
