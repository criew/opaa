package io.opaa.indexing.source.filesystem;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FilesystemExclusionsTest {

  private static final FilesystemExclusions DEFAULTS_ONLY =
      FilesystemExclusions.of(FilesystemSourceSettings.NONE);

  @Test
  void hiddenEntriesAndWindowsSystemFoldersAreAlwaysExcluded() {
    assertThat(DEFAULTS_ONLY.excludes(Path.of(".git"))).isTrue();
    assertThat(DEFAULTS_ONLY.excludes(Path.of("Projekte", ".shortcut-targets-by-id"))).isTrue();
    assertThat(DEFAULTS_ONLY.excludes(Path.of(".DS_Store"))).isTrue();
    assertThat(DEFAULTS_ONLY.excludes(Path.of("$RECYCLE.BIN"))).isTrue();
    assertThat(DEFAULTS_ONLY.excludes(Path.of("$Recycle.Bin"))).isTrue();
    assertThat(DEFAULTS_ONLY.excludes(Path.of("System Volume Information"))).isTrue();
  }

  @Test
  void ordinaryEntriesAreNotExcludedByDefault() {
    assertThat(DEFAULTS_ONLY.excludes(Path.of("Bericht.pdf"))).isFalse();
    assertThat(DEFAULTS_ONLY.excludes(Path.of("Archiv", "2020", "Plan.docx"))).isFalse();
    assertThat(DEFAULTS_ONLY.excludes(Path.of("Version 1.2"))).isFalse();
  }

  @Test
  void patternsMatchThePathRelativeToTheSourceDirectoryWithSlashAsSeparator() {
    FilesystemExclusions exclusions = exclusions("Archiv/2020/*.pdf", "*.tmp");

    assertThat(exclusions.excludes(Path.of("Archiv", "2020", "Plan.pdf"))).isTrue();
    assertThat(exclusions.excludes(Path.of("Archiv", "2021", "Plan.pdf"))).isFalse();
    assertThat(exclusions.excludes(Path.of("notiz.tmp"))).isTrue();
    // a single star stays within one directory level
    assertThat(exclusions.excludes(Path.of("Unterordner", "notiz.tmp"))).isFalse();
  }

  @Test
  void aTrailingDoubleStarAlsoExcludesTheDirectoryItselfSoItIsNeverEntered() {
    FilesystemExclusions exclusions = exclusions("Archiv/**");

    assertThat(exclusions.excludes(Path.of("Archiv"))).isTrue();
    assertThat(exclusions.excludes(Path.of("Archiv", "2020", "Plan.pdf"))).isTrue();
    assertThat(exclusions.excludes(Path.of("Archivierung.pdf"))).isFalse();
  }

  @Test
  void aLeadingDoubleStarAlsoMatchesAtTheTopLevel() {
    FilesystemExclusions exclusions = exclusions("**/*.tmp", "**/Entwürfe/**");

    assertThat(exclusions.excludes(Path.of("notiz.tmp"))).isTrue();
    assertThat(exclusions.excludes(Path.of("a", "b", "notiz.tmp"))).isTrue();
    assertThat(exclusions.excludes(Path.of("Entwürfe"))).isTrue();
    assertThat(exclusions.excludes(Path.of("Projekte", "Entwürfe"))).isTrue();
    assertThat(exclusions.excludes(Path.of("Projekte", "Entwürfe", "x.pdf"))).isTrue();
    assertThat(exclusions.excludes(Path.of("Projekte", "x.pdf"))).isFalse();
  }

  private static FilesystemExclusions exclusions(String... patterns) {
    return FilesystemExclusions.of(new FilesystemSourceSettings(List.of(patterns)));
  }
}
