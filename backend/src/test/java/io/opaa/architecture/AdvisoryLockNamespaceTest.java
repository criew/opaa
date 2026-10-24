package io.opaa.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Every Postgres advisory lock in the main code and the changesets takes a namespace of the
 * directory {@code db/advisory-lock-namespaces.yaml}, and no namespace is taken by a file the
 * directory does not name for it: two unrelated locks in one namespace would serialise against each
 * other, or worse, one would release the other's lock.
 *
 * <p>A file's namespaces are the literal first keys of its lock calls and the values of its {@code
 * int …LOCK_NAMESPACE = n} constants; a file that takes a lock and declares neither fails, so a
 * namespace is always readable where the lock is.
 */
class AdvisoryLockNamespaceTest {

  static final Path MAIN = Path.of("src", "main");
  static final String DIRECTORY = "/db/advisory-lock-namespaces.yaml";

  private static final Pattern LOCK_CALL =
      Pattern.compile("pg_(?:try_)?advisory_(?:xact_)?lock(?:_shared)?\\s*\\(");
  private static final Pattern LITERAL_NAMESPACE =
      Pattern.compile("pg_(?:try_)?advisory_(?:xact_)?lock(?:_shared)?\\s*\\(\\s*(\\d+)\\s*,");
  private static final Pattern NAMESPACE_CONSTANT =
      Pattern.compile("\\bint\\s+\\w*LOCK_NAMESPACE\\s*=\\s*(\\d+)\\s*;");

  record Entry(int number, String section, List<String> owners) {}

  @Test
  void everyAdvisoryLockTakesANamespaceTheDirectoryAssignsToItsFile() {
    assertThat(violations(scan(MAIN), directory())).isEmpty();
  }

  @Test
  void theDirectoryNamesNoNumberTwice() {
    Map<Integer, List<String>> sections = new TreeMap<>();
    directory()
        .forEach(
            entry ->
                sections
                    .computeIfAbsent(entry.number(), n -> new ArrayList<>())
                    .add(entry.section()));
    assertThat(sections)
        .allSatisfy((number, in) -> assertThat(in).as("namespace %d", number).hasSize(1));
  }

  @Test
  void aLockInANamespaceOfAnotherFileFails() {
    Map<String, Set<Integer>> scanned = new TreeMap<>(scan(MAIN));
    scanned.put("java/io/opaa/example/NewRepository.java", Set.of(202));

    assertThat(violations(scanned, directory()))
        .singleElement()
        .asString()
        .contains("java/io/opaa/example/NewRepository.java")
        .contains("202");
  }

  @Test
  void aLockInAnUnlistedNamespaceFails() {
    assertThat(
            violations(Map.of("java/io/opaa/example/NewRepository.java", Set.of(299)), directory()))
        .anySatisfy(
            violation -> assertThat(violation).contains("299").contains("not in the directory"));
  }

  @Test
  void aLockWithoutAReadableNamespaceFails() {
    assertThat(violations(Map.of("java/io/opaa/example/NewRepository.java", Set.of()), directory()))
        .anySatisfy(violation -> assertThat(violation).contains("LOCK_NAMESPACE"));
  }

  @Test
  void theNamespacesAreReadFromLiteralsAndConstants() {
    assertThat(namespacesOf("SELECT pg_advisory_lock(230, 0); SELECT pg_advisory_unlock(230, 0)"))
        .containsExactly(230);
    assertThat(
            namespacesOf(
                "int SOME_LOCK_NAMESPACE = 205;\n\"SELECT pg_try_advisory_xact_lock(\" + SOME\"))"))
        .containsExactly(205);
    assertThat(namespacesOf("the {@code pg_advisory_lock}/{@code pg_advisory_unlock} pair"))
        .isNull();
  }

  /** Every file under {@code root} that takes an advisory lock, with the namespaces it names. */
  static Map<String, Set<Integer>> scan(Path root) {
    Map<String, Set<Integer>> scanned = new TreeMap<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".java") && !name.endsWith(".yaml") && !name.endsWith(".sql")) {
          continue;
        }
        Set<Integer> namespaces = namespacesOf(Files.readString(file));
        if (namespaces != null) {
          scanned.put(root.relativize(file).toString().replace('\\', '/'), namespaces);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return scanned;
  }

  /** The namespaces a source names, or {@code null} when it takes no advisory lock at all. */
  static Set<Integer> namespacesOf(String source) {
    if (!LOCK_CALL.matcher(source).find()) {
      return null;
    }
    Set<Integer> namespaces = new TreeSet<>();
    for (Pattern pattern : List.of(LITERAL_NAMESPACE, NAMESPACE_CONSTANT)) {
      Matcher matcher = pattern.matcher(source);
      while (matcher.find()) {
        namespaces.add(Integer.parseInt(matcher.group(1)));
      }
    }
    return namespaces;
  }

  static List<String> violations(Map<String, Set<Integer>> scanned, List<Entry> directory) {
    Map<Integer, Entry> byNumber = new HashMap<>();
    directory.forEach(entry -> byNumber.put(entry.number(), entry));
    List<String> violations = new ArrayList<>();
    Map<Integer, Set<String>> takenBy = new TreeMap<>();
    scanned.forEach(
        (file, namespaces) -> {
          if (namespaces.isEmpty()) {
            violations.add(
                file
                    + ": takes an advisory lock without a readable namespace - name it as the literal"
                    + " first key or as an int ..._LOCK_NAMESPACE constant in this file");
          }
          for (int number : namespaces) {
            takenBy.computeIfAbsent(number, n -> new TreeSet<>()).add(file);
            Entry entry = byNumber.get(number);
            if (entry == null) {
              violations.add(
                  file + ": namespace " + number + " is not in the directory " + DIRECTORY);
            } else if (entry.section().equals("retired")) {
              violations.add(file + ": namespace " + number + " is retired and not taken again");
            } else if (!entry.owners().isEmpty() && !entry.owners().contains(file)) {
              violations.add(
                  file
                      + ": namespace "
                      + number
                      + " belongs to "
                      + entry.owners()
                      + " - take the next free number and add it to "
                      + DIRECTORY);
            }
          }
        });
    for (Entry entry : directory) {
      if (entry.section().equals("namespaces") && !takenBy.containsKey(entry.number())) {
        violations.add(
            "namespace "
                + entry.number()
                + " is listed for "
                + entry.owners()
                + " but no file takes it - move it to retired");
      }
      if (entry.section().equals("reserved")
          && entry.owners().isEmpty()
          && takenBy.getOrDefault(entry.number(), Set.of()).size() > 1) {
        violations.add(
            "reserved namespace " + entry.number() + " is taken by " + takenBy.get(entry.number()));
      }
    }
    return violations;
  }

  @SuppressWarnings("unchecked")
  static List<Entry> directory() {
    try (InputStream in = AdvisoryLockNamespaceTest.class.getResourceAsStream(DIRECTORY)) {
      Map<String, List<Map<String, Object>>> raw = new Yaml().load(in);
      List<Entry> entries = new ArrayList<>();
      raw.forEach(
          (section, list) ->
              list.forEach(
                  item ->
                      entries.add(
                          new Entry(
                              (Integer) item.get("number"),
                              section,
                              List.copyOf(
                                  (List<String>) item.getOrDefault("owners", List.of()))))));
      return entries;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
