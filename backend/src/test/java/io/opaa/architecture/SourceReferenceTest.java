package io.opaa.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Holds the names the bytecode does not show - imports, static ones included, and fully qualified
 * Javadoc references - to the layering and the modules of {@link ModularArchitecture}. A name
 * counts as an edge from its file's package to the package it names, checked by the same methods as
 * a compiled dependency, so an upward Javadoc reference is written as a {@code code} tag. Not
 * checked are a name in a package without a class of its own, an inlinable constant the code reads
 * fully qualified without an import, and the parameter types in a link of the form {@code
 * X#m(io.opaa.Type)}. The imports also join the compiled dependencies in the check that no two
 * packages below one top-level package form a cycle.
 */
class SourceReferenceTest {

  private static final Pattern REFERENCE =
      Pattern.compile(
          "^import\\s+(?:static\\s+)?(io\\.opaa\\.[\\w.]+)"
              + "|\\{@(?:link|linkplain|value)[\\s*]+(io\\.opaa\\.[\\w.]+)"
              + "|@(?:see|throws|exception)\\s+(io\\.opaa\\.[\\w.]+)",
          Pattern.MULTILINE);

  @Test
  void theMainSourcesNameNoPackageTheirsMayNotUse() throws IOException {
    assertThat(violations(Path.of("src/main/java"), "io.opaa", MainClasses.get())).isEmpty();
  }

  /** The constant is inlined, so the compiled classes alone pass. */
  @Test
  void aStaticImportAndJavadocReferencesUpwardAreReported() throws IOException {
    String root = "io.opaa.architecture.fixture.sourcereference";
    JavaClasses classes = new ClassFileImporter().importPackages(root);

    assertThat(new ModularArchitecture(root).noPackageDependsOnAHigherLayer().evaluate(classes))
        .matches(result -> !result.hasViolation(), "has no violation");
    assertThat(violations(Path.of("src/test/java"), root, classes))
        .containsExactly(
            "common/Low.java:3: common -> observability points upward in ModularArchitecture.LAYERS",
            "common/Low.java:7: common -> observability points upward in ModularArchitecture.LAYERS",
            "common/Low.java:9: common -> observability points upward in ModularArchitecture.LAYERS");
  }

  @Test
  void theMainSourcesFormNoCycleBetweenSubpackages() throws IOException {
    JavaClasses classes = MainClasses.get();
    assertThat(
            new ModularArchitecture("io.opaa")
                .subpackageCycleEdges(
                    classes, namedPackages(Path.of("src/main/java"), "io.opaa", classes)))
        .isEmpty();
  }

  /** An import used only in Javadoc and an inlined constant leave no trace in the bytecode. */
  @Test
  void aCycleOnlyTheSourcesShowIsReported() throws IOException {
    String root = "io.opaa.architecture.fixture.sourcecycle";
    JavaClasses classes = new ClassFileImporter().importPackages(root);
    ModularArchitecture architecture = new ModularArchitecture(root);

    assertThat(architecture.subpackagesAreFreeOfCycles().evaluate(classes))
        .matches(result -> !result.hasViolation(), "has no violation");
    assertThat(
            architecture.subpackageCycleEdges(
                classes, namedPackages(Path.of("src/test/java"), root, classes)))
        .containsExactly(
            Map.entry("common.a -> common.b", "common/a/Alpha.java:3"),
            Map.entry("common.b -> common.a", "common/b/Beta.java:3"));
  }

  /** Every name below {@code root} in the sources of {@code root} that breaks a rule. */
  private static List<String> violations(Path sources, String root, JavaClasses classes)
      throws IOException {
    ModularArchitecture architecture = new ModularArchitecture(root);
    Map<String, JavaClass> classOfPackage =
        classes.stream()
            .collect(Collectors.toMap(JavaClass::getPackageName, type -> type, (a, b) -> a));
    Path rootDirectory = sources.resolve(root.replace('.', '/'));
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(rootDirectory)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
        String source = Files.readString(file);
        JavaClass origin =
            classOfPackage.get(sources.relativize(file.getParent()).toString().replace('/', '.'));
        Matcher reference = REFERENCE.matcher(source);
        while (origin != null && reference.find()) {
          int group = reference.group(1) != null ? 1 : reference.group(2) != null ? 2 : 3;
          JavaClass target = classOfPackage.get(packageOf(reference.group(group)));
          if (target == null) {
            continue;
          }
          long line = source.substring(0, reference.start(group)).lines().count();
          Stream.of(
                  architecture.upwardEdge(origin, target),
                  architecture.forbiddenModuleEdge(origin, target))
              .filter(reason -> reason != null)
              .forEach(
                  reason ->
                      violations.add(rootDirectory.relativize(file) + ":" + line + ": " + reason));
        }
      }
    }
    return violations;
  }

  /**
   * The packages below {@code root} each package imports in its sources, both fully qualified, with
   * the first importing file and line as evidence. A fully qualified Javadoc link is no edge here.
   */
  private static Map<String, Map<String, String>> namedPackages(
      Path sources, String root, JavaClasses classes) throws IOException {
    Set<String> packages =
        classes.stream().map(JavaClass::getPackageName).collect(Collectors.toSet());
    Path rootDirectory = sources.resolve(root.replace('.', '/'));
    Map<String, Map<String, String>> named = new TreeMap<>();
    try (Stream<Path> files = Files.walk(rootDirectory)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
        String source = Files.readString(file);
        String origin = sources.relativize(file.getParent()).toString().replace('/', '.');
        Matcher reference = REFERENCE.matcher(source);
        while (packages.contains(origin) && reference.find()) {
          String target = reference.group(1) == null ? null : packageOf(reference.group(1));
          if (packages.contains(target)) {
            long line = source.substring(0, reference.start(1)).lines().count();
            named
                .computeIfAbsent(origin, key -> new TreeMap<>())
                .putIfAbsent(target, rootDirectory.relativize(file) + ":" + line);
          }
        }
      }
    }
    return named;
  }

  /** The leading lowercase segments of a qualified name: its package. */
  private static String packageOf(String name) {
    return Stream.of(name.split("\\."))
        .takeWhile(segment -> !segment.isEmpty() && Character.isLowerCase(segment.charAt(0)))
        .collect(Collectors.joining("."));
  }
}
