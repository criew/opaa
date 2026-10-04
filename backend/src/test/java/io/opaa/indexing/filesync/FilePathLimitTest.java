package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FilePathLimitTest {

  @Test
  void oneByteCharactersEndAtTheColumnWidth() {
    assertThat(FilePathLimit.fits("a".repeat(2000))).isTrue();
    assertThat(FilePathLimit.fits("a".repeat(2001))).isFalse();
    assertThat(FilePathLimit.cut("a".repeat(2500))).hasSize(2000);
  }

  @Test
  void twoByteCharactersEndAtTheIndexEntry() {
    assertThat(FilePathLimit.fits("Ж".repeat(1338))).isTrue();
    assertThat(FilePathLimit.fits("Ж".repeat(1339))).isFalse();
    assertThat(FilePathLimit.cut("Ж".repeat(1500))).isEqualTo("Ж".repeat(1338));
  }

  @Test
  void threeByteCharactersEndAtTheIndexEntry() {
    assertThat(FilePathLimit.fits("語".repeat(892))).isTrue();
    assertThat(FilePathLimit.fits("語".repeat(893))).isFalse();
    assertThat(FilePathLimit.cut("x" + "語".repeat(1000))).isEqualTo("x" + "語".repeat(891));
  }

  @Test
  void theCutNeverSplitsACodePoint() {
    String cut = FilePathLimit.cut("😀".repeat(1000));

    assertThat(cut).isEqualTo("😀".repeat(669));
    assertThat(cut.getBytes(StandardCharsets.UTF_8)).hasSize(2676);
    assertThat(FilePathLimit.fits(cut)).isTrue();
  }

  @Test
  void aPathThatFitsIsKeptWhole() {
    String path = "smb://server/freigabe/Akten/Bescheid.txt";

    assertThat(FilePathLimit.fits(path)).isTrue();
    assertThat(FilePathLimit.cut(path)).isEqualTo(path);
  }
}
