package io.opaa.indexing.source.filesystem;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Which document keys an unreadable directory or file shields from the reconciliation. */
class UnreadableAreasTest {

  @TempDir Path root;

  @Test
  void aDirectoryCoversItselfAndEverythingBelowItButNoSiblingSharingItsNamePrefix() {
    Path locked = root.resolve("a").resolve("b");
    var areas = new UnreadableAreas(List.of(locked));

    assertThat(areas.test(locked.toString())).isTrue();
    assertThat(areas.test(locked.resolve("x.txt").toString())).isTrue();
    assertThat(areas.test(locked.resolve("c").resolve("y.pdf").toString())).isTrue();
    assertThat(areas.test(root.resolve("a").resolve("bc").resolve("x.txt").toString())).isFalse();
    assertThat(areas.test(root.resolve("a").resolve("b.txt").toString())).isFalse();
    assertThat(areas.test(root.resolve("a").resolve("x.txt").toString())).isFalse();
  }

  @Test
  void aSingleUnreadableFileCoversItselfAndItsAttachmentKeysOnly() {
    Path file = root.resolve("mail.eml");
    var areas = new UnreadableAreas(List.of(file));

    assertThat(areas.test(file.toString())).isTrue();
    assertThat(areas.test(file + "/0/anlage.pdf")).isTrue();
    assertThat(areas.test(root.resolve("mail.eml.bak").toString())).isFalse();
  }

  @Test
  void keysAndAreasAreComparedNormalized() {
    var areas = new UnreadableAreas(List.of(root.resolve("a").resolve("..").resolve("b")));

    assertThat(areas.test(root.resolve("b").resolve("x.txt").toString())).isTrue();
    assertThat(areas.test(root.resolve("c").resolve("..").resolve("b").resolve("x.txt").toString()))
        .isTrue();
    assertThat(areas.test(root.resolve("b").resolve("..").resolve("c.txt").toString())).isFalse();
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void onWindowsTheComparisonIgnoresCaseAndSeparatorStyle() {
    var areas = new UnreadableAreas(List.of(Path.of("C:\\Daten\\Gesperrt")));

    assertThat(areas.test("c:/daten/gesperrt/x.txt")).isTrue();
    assertThat(areas.test("C:\\Daten\\Gesperrt2\\x.txt")).isFalse();
  }

  @Test
  void aKeyThatIsNoPathOfThisPlatformMatchesNothing() {
    var areas = new UnreadableAreas(List.of(root.resolve("a")));

    assertThat(areas.test("\0")).isFalse();
  }
}
