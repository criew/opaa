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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every logical module carries a short {@code AGENTS.md} in its main package, next to a {@code
 * CLAUDE.md} that imports it; every further top-level package of the module has a {@code CLAUDE.md}
 * that imports the module's file, so the instructions load wherever a file of the module is read.
 */
class ModuleAgentsFileTest {

  private static final Path SOURCES = Path.of("src/main/java/io/opaa");

  private static final int MAX_LINES = 60;

  /** The package, relative to {@code io.opaa}, whose directory holds the module's AGENTS.md. */
  private static final Map<Module, String> MAIN_PACKAGES =
      Map.ofEntries(
          entry(Module.FOUNDATION, "common"),
          entry(Module.IDENTITY, "auth"),
          entry(Module.RIGHTS, "permission"),
          entry(Module.KNOWLEDGE, "knowledge"),
          entry(Module.CONNECTORS, CONNECTOR_PARENT),
          entry(Module.WORKSPACE, "space"),
          entry(Module.LIBRARY, "library"),
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
      assertThat(contentOf(directory.resolve("CLAUDE.md")))
          .as("CLAUDE.md next to %s", agents)
          .isEqualTo("@AGENTS.md");
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
      assertThat(contentOf(directory.resolve("CLAUDE.md")))
          .as("CLAUDE.md of package %s imports the file of module %s", pkg, entry.getValue())
          .isEqualTo("@../" + main.replace('.', '/') + "/AGENTS.md");
      assertThat(directory.resolve("AGENTS.md"))
          .as("module %s keeps one AGENTS.md, in package %s", entry.getValue(), main)
          .doesNotExist();
    }
  }

  private static Path directoryOf(String pkg) {
    return SOURCES.resolve(pkg.replace('.', '/'));
  }

  private static String contentOf(Path file) throws IOException {
    assertThat(file).isRegularFile();
    return Files.readString(file, StandardCharsets.UTF_8).strip();
  }
}
