package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;

/**
 * {@link S3KeyPatterns}: include and exclude globs on the full object key, matched level by level
 * ({@code /} separates levels) in time bounded by pattern times key length.
 */
class S3KeyPatternsTest {

  private static S3KeyPatterns patterns(List<String> include, List<String> exclude) {
    return S3KeyPatterns.of(
        new S3SourceSettings(null, true, List.of(S3Scope.of("dokumente", "")), include, exclude));
  }

  // a runaway regex ignores interrupts; a separate thread lets the timeout fail the test anyway
  @Test
  @Timeout(value = 2, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
  void manyStarsWithLiteralsBetweenThemDoNotBacktrackCombinatorially() {
    String pattern = "*a".repeat(127) + "b";
    assertThat(pattern).hasSize(S3SourceSettings.MAX_PATTERN_LENGTH);
    String key = "a".repeat(250);

    S3KeyPatterns include = patterns(List.of(pattern), null);
    S3KeyPatterns exclude = patterns(null, List.of(pattern));
    for (int i = 0; i < 1000; i++) {
      assertThat(include.admits(key)).isFalse();
      assertThat(exclude.admits(key)).isTrue();
    }
  }

  @Test
  @Timeout(value = 2, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
  void manyDoubleStarLevelsDoNotBacktrackCombinatorially() {
    String pattern = "**/a/".repeat(50) + "b";
    String key = "a/".repeat(200) + "c";

    S3KeyPatterns include = patterns(List.of(pattern), null);
    for (int i = 0; i < 100; i++) {
      assertThat(include.admits(key)).isFalse();
    }
  }

  @Test
  void aDoubleStarLevelStandsForAnyNumberOfLevelsIncludingNone() {
    S3KeyPatterns patterns = patterns(List.of("**/*.pdf"), null);

    assertThat(patterns.admits("wurzel.pdf")).isTrue();
    assertThat(patterns.admits("2025/q1/protokoll.pdf")).isTrue();
    assertThat(patterns.admits("2025/q1/notiz.txt")).isFalse();
  }

  @Test
  void aSingleStarStaysWithinOneLevel() {
    S3KeyPatterns patterns = patterns(List.of("*.pdf"), null);

    assertThat(patterns.admits("wurzel.pdf")).isTrue();
    assertThat(patterns.admits("2025/protokoll.pdf")).isFalse();
  }

  @Test
  void aTrailingDoubleStarCoversEverythingBelowButNotAKeyNamedLikeTheFolder() {
    S3KeyPatterns patterns = patterns(null, List.of("Archiv/**"));

    assertThat(patterns.admits("Archiv/2020/alt.pdf")).isFalse();
    assertThat(patterns.admits("Archiv")).isTrue();
    assertThat(patterns.admits("Archiv2020/alt.pdf")).isTrue();
  }

  @Test
  void matchingIsCaseSensitive() {
    S3KeyPatterns patterns = patterns(List.of("**/*.PDF"), null);

    assertThat(patterns.admits("2025/protokoll.PDF")).isTrue();
    assertThat(patterns.admits("2025/protokoll.pdf")).isFalse();
  }

  @Test
  void anExcludePatternWinsOverAnIncludePattern() {
    S3KeyPatterns patterns = patterns(List.of("**/*.pdf"), List.of("**/entwurf-*"));

    assertThat(patterns.admits("2025/protokoll.pdf")).isTrue();
    assertThat(patterns.admits("2025/entwurf-protokoll.pdf")).isFalse();
  }

  @Test
  void withoutPatternsEveryKeyIsAdmitted() {
    assertThat(patterns(null, null).admits("2025//seltsam/")).isTrue();
  }
}
