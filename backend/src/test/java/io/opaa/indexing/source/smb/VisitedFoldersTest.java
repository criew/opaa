package io.opaa.indexing.source.smb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The folder ids of a walk survive their text form, whatever their size and sign. */
class VisitedFoldersTest {

  @Test
  void idsOfEverySizeAndSignSurviveTheRoundTrip() {
    Set<Long> ids = new HashSet<>(Set.of(1L, 2L, 36L, Long.MAX_VALUE, Long.MIN_VALUE, -2L));
    Random random = new Random(2202);
    for (int i = 0; i < 1000; i++) {
      ids.add(random.nextLong());
    }

    assertThat(VisitedFolders.decode(VisitedFolders.encode(ids))).isEqualTo(ids);
  }

  @Test
  void anEmptySetIsAnEmptyText() {
    assertThat(VisitedFolders.encode(Set.of())).isEmpty();
    assertThat(VisitedFolders.decode("")).isEmpty();
  }

  @Test
  void closeIdsTakeFewCharacters() {
    Set<Long> ids = new HashSet<>();
    for (long id = 1_000_000; id < 1_010_000; id++) {
      ids.add(id);
    }

    assertThat(VisitedFolders.encode(ids)).hasSizeLessThan(25_000);
  }

  @Test
  void aTextItDidNotWriteIsRefused() {
    assertThatThrownBy(() -> VisitedFolders.decode("1.$"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
