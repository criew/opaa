package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SourceGlobTest {

  @Test
  @Timeout(value = 2, unit = TimeUnit.SECONDS)
  void manyStarsWithLiteralsBetweenThemDoNotBacktrackCombinatorially() {
    // the JDK glob needs hours for this pair; matching here is bounded by pattern times name length
    String pattern = "*a".repeat(60) + "b";
    String name = "a".repeat(250);

    SourceGlob glob = SourceGlob.compile(pattern);
    for (int i = 0; i < 1000; i++) {
      assertThat(glob.matches(List.of(name), false)).isFalse();
    }
  }

  @Test
  @Timeout(value = 2, unit = TimeUnit.SECONDS)
  void manyDoubleStarLevelsDoNotBacktrackCombinatorially() {
    String pattern = "**/a/".repeat(40) + "b";
    List<String> levels = Collections.nCopies(200, "a");

    SourceGlob glob = SourceGlob.compile(pattern);
    for (int i = 0; i < 100; i++) {
      assertThat(glob.matches(levels, false)).isFalse();
    }
  }

  @Test
  void aDoubleStarInsideALevelIsRefusedBecauseItWouldNotSpanLevels() {
    assertThatThrownBy(() -> SourceGlob.compile("**.pdf"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ganze Ebene");
    assertThatThrownBy(() -> SourceGlob.compile("Archiv/a**b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ganze Ebene");
    assertThat(SourceGlob.compile("[*]*").matches(List.of("*x"), false)).isTrue();
  }
}
