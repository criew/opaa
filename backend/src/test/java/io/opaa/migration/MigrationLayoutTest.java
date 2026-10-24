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
import org.junit.jupiter.api.Test;

/**
 * The layout of {@code db/migration/}: one directory per module, the module baselines {@code
 * V1.<n>__<module>_baseline.sql} numbered in module order, every later script named {@code
 * V<YYYY>.<MM>.<DD>.<n>__<topic>.sql} after the day it was written, every version used once. Reads
 * the source tree; no database.
 */
class MigrationLayoutTest {

  private static final Path MIGRATION_ROOT = Path.of("src/main/resources/db/migration");
  private static final Pattern BASELINE = Pattern.compile("V1\\.(\\d+)__([a-z]+)_baseline\\.sql");
  private static final Pattern DATED =
      Pattern.compile(
          "V(\\d{4})\\.(\\d{2})\\.(\\d{2})\\.([1-9]\\d*)__[a-z0-9]+(_[a-z0-9]+)*\\.sql");

  @Test
  void theMigrationRootHoldsOneDirectoryPerModuleAndNothingElse() throws IOException {
    List<String> modules =
        Stream.of(Module.values()).map(module -> module.name().toLowerCase(Locale.ROOT)).toList();
    try (Stream<Path> entries = Files.list(MIGRATION_ROOT)) {
      assertThat(entries)
          .as("every entry under db/migration/ is a directory named after a module")
          .allMatch(path -> Files.isDirectory(path))
          .allMatch(path -> modules.contains(path.getFileName().toString()));
    }
  }

  /**
   * The baselines run in the order of {@link Module}, in which every allowed module edge points to
   * an earlier module - so a module's baseline runs after those of every module it may reference.
   */
  @Test
  void theBaselinesRunInModuleOrderOnePerModule() {
    List<String> baselineModules =
        Migrations.baselines().stream().map(Migrations::moduleOf).toList();
    List<String> moduleOrder =
        Stream.of(Module.values())
            .map(module -> module.name().toLowerCase(Locale.ROOT))
            .filter(baselineModules::contains)
            .toList();

    assertThat(baselineModules).isEqualTo(moduleOrder);
    for (String baseline : Migrations.baselines()) {
      Matcher matcher = BASELINE.matcher(baseline.substring(baseline.lastIndexOf('/') + 1));
      assertThat(matcher.matches()).as(baseline).isTrue();
      assertThat(matcher.group(2)).as(baseline).isEqualTo(Migrations.moduleOf(baseline));
      assertThat(Integer.parseInt(matcher.group(1)))
          .as("%s is numbered by its place in module order", baseline)
          .isEqualTo(moduleOrder.indexOf(matcher.group(2)) + 1);
    }
  }

  /**
   * A module directory holds only scripts, no subdirectory; every script after the baselines is
   * versioned by the day it was written and a running number, so two scripts of a day stay apart.
   */
  @Test
  void everyLaterScriptIsVersionedByDateAndNumberAndNamedByTopic() throws IOException {
    List<String> misnamed = new ArrayList<>();
    try (Stream<Path> entries = Files.walk(MIGRATION_ROOT, 2)) {
      entries
          .filter(path -> !path.equals(MIGRATION_ROOT) && !path.getParent().equals(MIGRATION_ROOT))
          .forEach(
              path -> {
                String name = path.getFileName().toString();
                Matcher dated = DATED.matcher(name);
                boolean valid =
                    !Files.isDirectory(path)
                        && (BASELINE.matcher(name).matches()
                            || dated.matches()
                                && isDate(dated.group(1), dated.group(2), dated.group(3)));
                if (!valid) {
                  misnamed.add(MIGRATION_ROOT.relativize(path).toString().replace('\\', '/'));
                }
              });
    }

    assertThat(misnamed)
        .as("expected <module>/V<YYYY>.<MM>.<DD>.<n>__<topic>.sql (lower case, underscores)")
        .isEmpty();
  }

  /**
   * Flyway refuses two scripts of one version; across the module directories nothing else would.
   */
  @Test
  void everyVersionIsUsedOnce() {
    Set<String> versions = new HashSet<>();
    List<String> duplicates = new ArrayList<>();
    for (String script : Migrations.all()) {
      if (!versions.add(Migrations.version(script).getVersion())) {
        duplicates.add(script);
      }
    }

    assertThat(duplicates).isEmpty();
  }

  /** The fixture of a delta test: every script but the one under test, in version order. */
  @Test
  void aFreshInstallationAppliesTheScriptsInVersionOrder() {
    List<String> all = Migrations.all();
    String knowledge = Migrations.baselines().get(3);

    assertThat(Migrations.before(knowledge)).isEqualTo(all.subList(0, all.indexOf(knowledge)));
    assertThat(Migrations.before(knowledge).stream().map(Migrations::moduleOf))
        .containsExactly("foundation", "identity", "rights");
    assertThat(Migrations.except(knowledge)).doesNotContain(knowledge).hasSize(all.size() - 1);
  }

  private static boolean isDate(String year, String month, String day) {
    try {
      LocalDate.parse(year + "-" + month + "-" + day);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }
}
