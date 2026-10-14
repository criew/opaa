package io.opaa.architecture;

import static io.opaa.architecture.ModularArchitecture.CONNECTOR_PARENT;
import static io.opaa.architecture.ModularArchitecture.MODULES;
import static io.opaa.architecture.ModularArchitecture.ROOT;
import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.architecture.ModularArchitecture.Module;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every logical module carries a short {@code AGENTS.md} in its main package, next to a {@code
 * CLAUDE.md} that imports it; every further top-level package of the module has a {@code CLAUDE.md}
 * that imports the module's file, so the instructions load wherever a file of the module is read.
 * Every ADR the file names, and every link it holds into {@code docs/decisions}, exists there.
 */
class ModuleAgentsFileTest {

  private static final Path SOURCES = Path.of("src/main/java/io/opaa");

  private static final int MAX_LINES = 60;

  /** "Pakete (`io.opaa.*`): a, b." - group 1 the prefix below io.opaa, group 2 the list. */
  private static final Pattern PACKAGE_LINE =
      Pattern.compile("Pakete \\(`io\\.opaa\\.([a-z.]*)\\*`\\): ([a-z0-9, ]+)");

  private static final Path DECISIONS = Path.of("../docs/decisions");

  /** "ADR-0044" - group 1 the number, which a file name under docs/decisions starts with. */
  private static final Pattern ADR_NUMBER = Pattern.compile("ADR-(\\d{4})");

  /** "](../docs/decisions/0044-x.md)" - group 1 the link, relative to the linking file. */
  private static final Pattern DECISION_LINK =
      Pattern.compile("\\]\\(([^)\\s]*decisions/[^)\\s]*)\\)");

  /** The package, relative to {@code io.opaa}, whose directory holds the module's AGENTS.md. */
  private static final Map<Module, String> MAIN_PACKAGES =
      Map.ofEntries(
          entry(Module.FOUNDATION, "common"),
          entry(Module.FORMAT, "format"),
          entry(Module.IDENTITY, "auth"),
          entry(Module.RIGHTS, "permission"),
          entry(Module.KNOWLEDGE, "knowledge"),
          entry(Module.CONNECTORS, CONNECTOR_PARENT),
          entry(Module.CONNECTIONS, "connection"),
          entry(Module.WORKSPACE, "space"),
          entry(Module.LIBRARY, "library"),
          entry(Module.RETRIEVAL, "retrieval"),
          entry(Module.ASSISTANT, "query"),
          entry(Module.EXTERNAL, "externalaccess"),
          entry(Module.APP, "api"));

  @Test
  void everyModuleNamesAMainPackageOfItsOwn() {
    assertThat(MAIN_PACKAGES.keySet()).containsExactlyInAnyOrder(Module.values());
    MAIN_PACKAGES.forEach(
        (module, main) -> {
          if (module == Module.CONNECTORS) {
            assertThat(main).isEqualTo(CONNECTOR_PARENT);
          } else {
            assertThat(MODULES.get(main)).as("module of package %s", main).isEqualTo(module);
          }
        });
  }

  @Test
  void everyModuleHasAShortAgentsFileThatClaudeCodeLoads() throws IOException {
    for (Map.Entry<Module, String> module : MAIN_PACKAGES.entrySet()) {
      Path directory = directoryOf(module.getValue());
      Path agents = directory.resolve("AGENTS.md");
      assertThat(agents).as("AGENTS.md of module %s", module.getKey()).isRegularFile();
      List<String> lines = Files.readAllLines(agents, StandardCharsets.UTF_8);
      assertThat(lines)
          .as("%s has at most %d lines - move detail into ADRs or the Handbuch", agents, MAX_LINES)
          .hasSizeLessThanOrEqualTo(MAX_LINES)
          .anyMatch(line -> !line.isBlank());
      Path claude = directory.resolve("CLAUDE.md");
      assertThat(claude)
          .as("%s is missing; create it with the single line @AGENTS.md", claude)
          .isRegularFile();
      assertThat(contentOf(claude)).as("CLAUDE.md next to %s", agents).isEqualTo("@AGENTS.md");
    }
  }

  /** The "Pakete" line names exactly the packages the module holds, so it cannot drift. */
  @Test
  void everyAgentsFileNamesThePackagesOfItsModule() throws IOException {
    for (Map.Entry<Module, String> module : MAIN_PACKAGES.entrySet()) {
      Path agents = directoryOf(module.getValue()).resolve("AGENTS.md");
      String text = Files.readString(agents, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
      Matcher line = PACKAGE_LINE.matcher(text);
      assertThat(line.find())
          .as("%s starts with a line \"Pakete (`io.opaa.*`): a, b.\"", agents)
          .isTrue();
      boolean connectors = module.getKey() == Module.CONNECTORS;
      assertThat(line.group(1))
          .as("package prefix in %s", agents)
          .isEqualTo(connectors ? CONNECTOR_PARENT + "." : "");
      Set<String> named = new TreeSet<>(Arrays.asList(line.group(2).strip().split(",\\s*")));
      assertThat(named)
          .as("packages named in %s", agents)
          .isEqualTo(connectors ? connectorPackages() : packagesOf(module.getKey()));
    }
  }

  @Test
  void everyFurtherPackageOfAModuleImportsTheModuleFile() throws IOException {
    for (Map.Entry<String, Module> entry : MODULES.entrySet()) {
      String pkg = entry.getKey();
      String main = MAIN_PACKAGES.get(entry.getValue());
      if (pkg.equals(ROOT) || pkg.equals(main)) {
        continue;
      }
      Path directory = directoryOf(pkg);
      String expected = "@../" + main.replace('.', '/') + "/AGENTS.md";
      Path claude = directory.resolve("CLAUDE.md");
      assertThat(claude)
          .as("%s is missing; create it with the single line %s", claude, expected)
          .isRegularFile();
      assertThat(contentOf(claude))
          .as("CLAUDE.md of package %s imports the file of module %s", pkg, entry.getValue())
          .isEqualTo(expected);
      assertThat(directory.resolve("AGENTS.md"))
          .as("module %s keeps one AGENTS.md, in package %s", entry.getValue(), main)
          .doesNotExist();
    }
  }

  @Test
  void everyAdrReferenceInAnAgentsFileNamesAnExistingDecision() throws IOException {
    for (String main : MAIN_PACKAGES.values()) {
      Path agents = directoryOf(main).resolve("AGENTS.md");
      assertThat(deadAdrReferences(agents)).as("ADR references in %s", agents).isEmpty();
    }
  }

  @Test
  void aReferenceToAMissingDecisionIsReported() throws IOException {
    Path fixture =
        Path.of("src/test/java/io/opaa/architecture/fixture/adrreference/dead-adr-references.md");

    assertThat(deadAdrReferences(fixture))
        .containsExactly("ADR-0999", "../../../../../../../../../docs/decisions/0044-renamed.md");
  }

  /** Every ADR number in {@code file} without a decision, then every decision link to no file. */
  private static List<String> deadAdrReferences(Path file) throws IOException {
    Set<String> numbers;
    try (Stream<Path> decisions = Files.list(DECISIONS)) {
      numbers =
          decisions
              .map(decision -> decision.getFileName().toString())
              .filter(name -> name.matches("\\d{4}-.*\\.md"))
              .map(name -> name.substring(0, 4))
              .collect(Collectors.toSet());
    }
    String text = Files.readString(file, StandardCharsets.UTF_8);
    Stream<String> unknownNumbers =
        ADR_NUMBER
            .matcher(text)
            .results()
            .map(reference -> reference.group(1))
            .filter(number -> !numbers.contains(number))
            .map(number -> "ADR-" + number);
    Stream<String> brokenLinks =
        DECISION_LINK
            .matcher(text)
            .results()
            .map(link -> link.group(1))
            .filter(link -> !Files.isRegularFile(file.resolveSibling(link)));
    return Stream.concat(unknownNumbers, brokenLinks).distinct().toList();
  }

  private static Set<String> packagesOf(Module module) {
    return MODULES.entrySet().stream()
        .filter(entry -> entry.getValue() == module && !entry.getKey().equals(ROOT))
        .map(Map.Entry::getKey)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static Set<String> connectorPackages() throws IOException {
    try (Stream<Path> children = Files.list(directoryOf(CONNECTOR_PARENT))) {
      return children
          .filter(Files::isDirectory)
          .map(child -> child.getFileName().toString())
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }

  private static Path directoryOf(String pkg) {
    return SOURCES.resolve(pkg.replace('.', '/'));
  }

  private static String contentOf(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8).strip();
  }
}
