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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Holds the names the bytecode does not show - imports, static ones included, and fully qualified
 * Javadoc references - to the layering and the modules of {@link ModularArchitecture}. A name
 * counts as an edge from its file's package to the package it names, checked by the same methods as
 * a compiled dependency. A name in a package without a class of its own is not checked.
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

  /** The leading lowercase segments of a qualified name: its package. */
  private static String packageOf(String name) {
    return Stream.of(name.split("\\."))
        .takeWhile(segment -> !segment.isEmpty() && Character.isLowerCase(segment.charAt(0)))
        .collect(Collectors.joining("."));
  }
}
