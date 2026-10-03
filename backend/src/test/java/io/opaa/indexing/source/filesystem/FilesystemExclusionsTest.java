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
    assertThat(DEFAULTS_ONLY.excludes(Path.of(".git"), true)).isTrue();
    assertThat(DEFAULTS_ONLY.excludes(Path.of("Projekte", ".shortcut-targets-by-id"), true))
        .isTrue();
    assertThat(DEFAULTS_ONLY.excludes(Path.of(".DS_Store"), false)).isTrue();
    assertThat(DEFAULTS_ONLY.excludes(Path.of("$RECYCLE.BIN"), true)).isTrue();
    assertThat(DEFAULTS_ONLY.excludes(Path.of("$Recycle.Bin"), true)).isTrue();
    assertThat(DEFAULTS_ONLY.excludes(Path.of("System Volume Information"), true)).isTrue();
  }

  @Test
  void ordinaryEntriesAreNotExcludedByDefault() {
    assertThat(DEFAULTS_ONLY.excludes(Path.of("Bericht.pdf"), false)).isFalse();
    assertThat(DEFAULTS_ONLY.excludes(Path.of("Archiv", "2020", "Plan.docx"), false)).isFalse();
    assertThat(DEFAULTS_ONLY.excludes(Path.of("Version 1.2"), true)).isFalse();
  }

  @Test
  void patternsMatchThePathRelativeToTheSourceDirectoryWithSlashAsSeparator() {
    FilesystemExclusions exclusions = exclusions("Archiv/2020/*.pdf", "*.tmp");

    assertThat(exclusions.excludes(Path.of("Archiv", "2020", "Plan.pdf"), false)).isTrue();
    assertThat(exclusions.excludes(Path.of("Archiv", "2021", "Plan.pdf"), false)).isFalse();
    assertThat(exclusions.excludes(Path.of("notiz.tmp"), false)).isTrue();
    // a single star stays within one directory level
    assertThat(exclusions.excludes(Path.of("Unterordner", "notiz.tmp"), false)).isFalse();
  }

  @Test
  void aTrailingDoubleStarAlsoExcludesTheDirectoryItselfSoItIsNeverEntered() {
    FilesystemExclusions exclusions = exclusions("Archiv/**");

    assertThat(exclusions.excludes(Path.of("Archiv"), true)).isTrue();
    assertThat(exclusions.excludes(Path.of("Archiv", "2020", "Plan.pdf"), false)).isTrue();
    assertThat(exclusions.excludes(Path.of("Archivierung.pdf"), false)).isFalse();
  }

  @Test
  void theDirectoryRuleOfATrailingDoubleStarNeverExcludesAFile() {
    FilesystemExclusions exclusions = exclusions("Archiv*/**", "*/**");

    assertThat(exclusions.excludes(Path.of("Archiv2020"), true)).isTrue();
    assertThat(exclusions.excludes(Path.of("Archiv2020.pdf"), false)).isFalse();
    assertThat(exclusions.excludes(Path.of("Archiv"), false)).isFalse();
    assertThat(exclusions.excludes(Path.of("Bericht.pdf"), false)).isFalse();
    assertThat(exclusions.excludes(Path.of("Ordner", "Bericht.pdf"), false)).isTrue();
  }

  @Test
  void aDoubleStarLevelAlsoMatchesNoLevelAtAll() {
    FilesystemExclusions exclusions = exclusions("**/*.tmp", "**/Entwürfe/**", "a/**/z.txt");

    assertThat(exclusions.excludes(Path.of("notiz.tmp"), false)).isTrue();
    assertThat(exclusions.excludes(Path.of("a", "b", "notiz.tmp"), false)).isTrue();
    assertThat(exclusions.excludes(Path.of("Entwürfe"), true)).isTrue();
    assertThat(exclusions.excludes(Path.of("Projekte", "Entwürfe"), true)).isTrue();
    assertThat(exclusions.excludes(Path.of("Projekte", "Entwürfe", "x.pdf"), false)).isTrue();
    assertThat(exclusions.excludes(Path.of("Projekte", "x.pdf"), false)).isFalse();
    assertThat(exclusions.excludes(Path.of("a", "z.txt"), false)).isTrue();
    assertThat(exclusions.excludes(Path.of("a", "b", "c", "z.txt"), false)).isTrue();
  }

  @Test
  void classesAlternativesAndQuestionMarksWorkWithinALevelCaseSensitively() {
    FilesystemExclusions exclusions = exclusions("{Entwürfe,Papierkorb}/**", "Plan-20[0-9]?.pdf");

    assertThat(exclusions.excludes(Path.of("Papierkorb"), true)).isTrue();
    assertThat(exclusions.excludes(Path.of("Entwürfe", "x.pdf"), false)).isTrue();
    assertThat(exclusions.excludes(Path.of("Plan-2024.pdf"), false)).isTrue();
    assertThat(exclusions.excludes(Path.of("Plan-2A24.pdf"), false)).isFalse();
    assertThat(exclusions.excludes(Path.of("papierkorb"), true)).isFalse();
  }

  private static FilesystemExclusions exclusions(String... patterns) {
    return FilesystemExclusions.of(new FilesystemSourceSettings(List.of(patterns)));
  }
}
