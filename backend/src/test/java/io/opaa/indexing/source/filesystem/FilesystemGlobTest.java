package io.opaa.indexing.source.filesystem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class FilesystemGlobTest {

  @Test
  @Timeout(value = 2, unit = TimeUnit.SECONDS)
  void manyStarsWithLiteralsBetweenThemDoNotBacktrackCombinatorially() {
    // the JDK glob needs hours for this pair; matching here is bounded by pattern times name length
    String pattern = "*a".repeat(60) + "b";
    String name = "a".repeat(250);

    FilesystemGlob glob = FilesystemGlob.compile(pattern);
    for (int i = 0; i < 1000; i++) {
      assertThat(glob.matches(List.of(name), false)).isFalse();
    }
  }

  @Test
  @Timeout(value = 2, unit = TimeUnit.SECONDS)
  void manyDoubleStarLevelsDoNotBacktrackCombinatorially() {
    String pattern = "**/a/".repeat(40) + "b";
    List<String> levels = Collections.nCopies(200, "a");

    FilesystemGlob glob = FilesystemGlob.compile(pattern);
    for (int i = 0; i < 100; i++) {
      assertThat(glob.matches(levels, false)).isFalse();
    }
  }
}
