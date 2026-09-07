package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link SourceFolderPath}: which segment no folder row can carry, that a rejection discards the
 * already accepted segments too, and that only a capped chain is cut at the folder limit.
 */
class SourceFolderPathTest {

  @Test
  void acceptedSegmentsAreKeptOutermostFirst() {
    SourceFolderPath path = SourceFolderPath.of(List.of("2025", "protokolle"));

    assertThat(path.segments()).containsExactly("2025", "protokolle");
    assertThat(path.rejected()).isFalse();
    assertThat(path.truncated()).isFalse();
  }

  @Test
  void noSegmentsMeanTheRoot() {
    assertThat(SourceFolderPath.of(List.of())).isSameAs(SourceFolderPath.ROOT);
    assertThat(SourceFolderPath.capped(List.of())).isSameAs(SourceFolderPath.ROOT);
    assertThat(SourceFolderPath.ROOT.segments()).isEmpty();
    assertThat(SourceFolderPath.ROOT.rejected()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t", ".", "..", "a/b", "a\\b", "a\u0000b"})
  void aSegmentNoFolderRowCanCarryIsRejected(String segment) {
    assertThat(SourceFolderPath.rejects(segment)).isTrue();

    SourceFolderPath path = SourceFolderPath.of(List.of("2025", segment, "unten"));

    assertThat(path.rejected()).isTrue();
    assertThat(path.rejectedSegment()).isEqualTo(segment);
    // half a path is worse than none: the item belongs to the root, not into "2025"
    assertThat(path.segments()).isEmpty();
  }

  @Test
  void aSegmentWiderThanTheColumnIsRejectedAndOneExactlyAsWideIsNot() {
    String widest = "a".repeat(SourceFolderPath.MAX_SEGMENT_LENGTH);

    assertThat(SourceFolderPath.of(List.of(widest)).segments()).containsExactly(widest);
    assertThat(SourceFolderPath.of(List.of(widest + "a")).rejectedSegment())
        .isEqualTo(widest + "a");
  }

  @Test
  void onlyATraversalOrASeparatorCountsAsATraversalName() {
    assertThat(SourceFolderPath.isPathTraversalName(".")).isTrue();
    assertThat(SourceFolderPath.isPathTraversalName("..")).isTrue();
    assertThat(SourceFolderPath.isPathTraversalName("a/b")).isTrue();
    assertThat(SourceFolderPath.isPathTraversalName("a\\b")).isTrue();
    assertThat(SourceFolderPath.isPathTraversalName("...")).isFalse();
    assertThat(SourceFolderPath.isPathTraversalName(" ")).isFalse();
    assertThat(SourceFolderPath.rejects("Vergütung 2025")).isFalse();
  }

  @Test
  void rejectingNamesTheSegmentAndYieldsNoFolders() {
    SourceFolderPath path = SourceFolderPath.rejecting("%2E%2E");

    assertThat(path.rejected()).isTrue();
    assertThat(path.rejectedSegment()).isEqualTo("%2E%2E");
    assertThat(path.segments()).isEmpty();
    assertThat(path.truncated()).isFalse();
  }

  @Test
  void aCappedChainDeeperThanTheFolderLimitIsCutAtTheDeepestAllowedFolder() {
    List<String> deep = segments(SourceFolderPath.MAX_DEPTH + 2);

    SourceFolderPath path = SourceFolderPath.capped(deep);

    assertThat(path.segments())
        .hasSize(SourceFolderPath.MAX_DEPTH)
        .isEqualTo(deep.subList(0, SourceFolderPath.MAX_DEPTH));
    assertThat(path.truncated()).isTrue();
    assertThat(path.rejected()).isFalse();
    assertThat(SourceFolderPath.capped(segments(SourceFolderPath.MAX_DEPTH)).truncated()).isFalse();
  }

  @Test
  void aCappedChainJudgesOnlyTheSegmentsWithinTheLimit() {
    List<String> deep = new ArrayList<>(segments(SourceFolderPath.MAX_DEPTH));
    deep.add("..");

    SourceFolderPath path = SourceFolderPath.capped(deep);

    assertThat(path.rejected()).isFalse();
    assertThat(path.truncated()).isTrue();
    assertThat(path.segments()).hasSize(SourceFolderPath.MAX_DEPTH);
  }

  @Test
  void anUncappedChainNestsAsDeepAsTheSourceDoes() {
    List<String> deep = segments(SourceFolderPath.MAX_DEPTH + 5);

    SourceFolderPath path = SourceFolderPath.of(deep);

    assertThat(path.segments()).isEqualTo(deep);
    assertThat(path.truncated()).isFalse();
  }

  @Test
  void theSegmentsAreDetachedFromTheCallersList() {
    List<String> mutable = new ArrayList<>(List.of("a"));

    SourceFolderPath path = SourceFolderPath.of(mutable);
    mutable.add("b");

    assertThat(path.segments()).containsExactly("a");
  }

  private static List<String> segments(int count) {
    List<String> segments = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      segments.add("ebene" + i);
    }
    return segments;
  }
}
