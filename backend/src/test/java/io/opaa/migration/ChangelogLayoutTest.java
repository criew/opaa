package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.architecture.ModularArchitecture.Module;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import liquibase.changelog.ChangeSet;
import org.junit.jupiter.api.Test;

/**
 * The layout of {@code db/changelog/}: one directory per module in module order, files named {@code
 * YYYY-MM-DD-<topic>.yaml}, changeSet ids prefixed with module, date and topic. Reads the master as
 * Liquibase resolves it and the source tree next to it; no database.
 */
class ChangelogLayoutTest {

  private static final Path CHANGELOG_ROOT = Path.of("src/main/resources/db/changelog");
  private static final String MASTER_FILE = "db.changelog-master.yaml";
  private static final Pattern FILE_NAME =
      Pattern.compile("(\\d{4}-\\d{2}-\\d{2})-[a-z0-9]+(-[a-z0-9]+)*\\.yaml");

  /**
   * The master includes the module directories in the order of {@link Module}, in which every
   * allowed module edge points to an earlier module - so a module's changesets run after those of
   * every module it may reference.
   */
  @Test
  void theMasterRunsTheModuleDirectoriesInModuleOrder() throws IOException {
    List<String> masterOrder =
        MasterChangelog.files().stream().map(MasterChangelog::moduleOf).distinct().toList();
    List<String> directories;
    try (Stream<Path> entries = Files.list(CHANGELOG_ROOT)) {
      directories =
          entries.filter(Files::isDirectory).map(path -> path.getFileName().toString()).toList();
    }
    List<String> moduleOrder =
        Stream.of(Module.values())
            .map(module -> module.name().toLowerCase(Locale.ROOT))
            .filter(directories::contains)
            .toList();

    assertThat(directories)
        .as("every directory under db/changelog/ is named after a module")
        .allMatch(moduleOrder::contains);
    assertThat(masterOrder).as("the master includes every module directory").isEqualTo(moduleOrder);
  }

  @Test
  void theChangelogRootHoldsNothingButTheMasterAndTheModuleDirectories() throws IOException {
    try (Stream<Path> entries = Files.list(CHANGELOG_ROOT)) {
      assertThat(entries.filter(Files::isRegularFile).map(path -> path.getFileName().toString()))
          .containsExactly(MASTER_FILE);
    }
  }

  /**
   * A module directory holds only changelog files, no subdirectory, each named after the day it was
   * written and its topic - which keeps two files of the same day apart and orders them.
   */
  @Test
  void everyChangelogFileIsNamedByDateAndTopic() throws IOException {
    List<String> misnamed = new ArrayList<>();
    try (Stream<Path> entries = Files.walk(CHANGELOG_ROOT, 2)) {
      entries
          .filter(path -> !path.equals(CHANGELOG_ROOT) && !path.getParent().equals(CHANGELOG_ROOT))
          .forEach(
              path -> {
                String name = path.getFileName().toString();
                Matcher matcher = FILE_NAME.matcher(name);
                if (Files.isDirectory(path) || !matcher.matches() || !isDate(matcher.group(1))) {
                  misnamed.add(CHANGELOG_ROOT.relativize(path).toString().replace('\\', '/'));
                }
              });
    }

    assertThat(misnamed).as("expected <module>/YYYY-MM-DD-<topic>.yaml").isEmpty();
  }

  /**
   * Every changeSet id starts with its module, the date and the topic of its file, and is unique
   * across the master; every author is {@code opaa}.
   */
  @Test
  void everyChangeSetIdIsPrefixedWithModuleDateAndTopic() {
    List<String> violations = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (ChangeSet changeSet : MasterChangelog.changeSets()) {
      String file = changeSet.getFilePath();
      String stem = file.substring(file.lastIndexOf('/') + 1).replaceFirst("\\.yaml$", "");
      String prefix = MasterChangelog.moduleOf(file) + "-" + stem;
      String id = changeSet.getId();
      if (!id.equals(prefix) && !id.startsWith(prefix + "-")) {
        violations.add(id + " in " + file + " does not start with " + prefix);
      }
      if (!ids.add(id)) {
        violations.add(id + " is not unique");
      }
      if (!"opaa".equals(changeSet.getAuthor())) {
        violations.add(id + " has author " + changeSet.getAuthor() + ", not opaa");
      }
    }

    assertThat(violations).isEmpty();
  }

  /** The fixture of a delta test: all lower modules, then the earlier files of its own module. */
  @Test
  void aDeltaTestStartsFromEverythingTheMasterRunsBeforeItsFile() {
    String knowledgeBaseline = "db/changelog/knowledge/2026-09-27-baseline.yaml";
    List<String> files = MasterChangelog.files();

    List<String> before = MasterChangelog.filesBefore(knowledgeBaseline);

    assertThat(before).isEqualTo(files.subList(0, files.indexOf(knowledgeBaseline)));
    assertThat(before.stream().map(MasterChangelog::moduleOf).distinct())
        .containsExactly("foundation", "identity", "rights");
  }

  private static boolean isDate(String value) {
    try {
      LocalDate.parse(value);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }
}
