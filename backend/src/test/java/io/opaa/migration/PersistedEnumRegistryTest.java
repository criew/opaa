package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.DirectorySyncOutcome;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Transient;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.yaml.snakeyaml.Yaml;

/**
 * Persisted enums under the expand/contract rule (backend/docs/migrationen.md, "Enum-Werte"): a
 * value stored by {@code @Enumerated(STRING)} or a converter that the release still running beside
 * a new one does not know makes every read of its row fail there - a notification list, an audit
 * page, a directory status. No migration shows that, so the registry {@value #REGISTRY} lists every
 * persisted enum with the values the previous release reads ({@code readable}) and the values this
 * one knows but writes nowhere yet ({@code pending}): the expand step. A value moves from {@code
 * pending} to {@code readable} once a release that knows it has shipped; only then may code write
 * it. Each entry carries the assessment why the old release does not read an unknown value
 * tolerantly instead.
 */
class PersistedEnumRegistryTest {

  static final String REGISTRY = "db/persisted-enums.yaml";

  /** Why an unknown value is not read with a fallback: the reason classes of migrationen.md. */
  enum Assessment {
    /** Rights, roles, account and token states: a fallback would move what someone may do. */
    SECURITY,
    /** Protocols and histories: a fallback would falsify the evidence. */
    AUDIT,
    /** State of a row that is written again: the old release would store the fallback back. */
    STATE
  }

  record Entry(
      Set<String> attributes, Assessment assessment, Set<String> readable, Set<String> pending) {}

  record Persisted(Set<String> attributes, Set<String> values) {}

  private static final List<Path> MAIN_SOURCES =
      List.of(Path.of("src", "main", "java"), Path.of("..", "opaa-api", "src", "main", "java"));

  @Test
  void everyPersistedEnumIsRegisteredWithTheValuesThePreviousReleaseReads() {
    List<String> problems = check(discover(), load(REGISTRY));

    assertThat(problems).withFailMessage(() -> report(problems)).isEmpty();
  }

  @Test
  void aPendingValueIsWrittenNowhereYet() throws IOException {
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, Entry> entry : load(REGISTRY).entrySet()) {
      for (String value : entry.getValue().pending()) {
        problems.addAll(usesOf(entry.getKey(), value));
      }
    }

    assertThat(problems)
        .as(
            "a pending value is written only from the release after the one that ships it - move"
                + " it to readable in %s once that release exists",
            REGISTRY)
        .isEmpty();
  }

  @Test
  void theSearchForUsesFindsAValueTheCodeWrites() throws IOException {
    assertThat(usesOf(DirectorySyncOutcome.class.getName(), "FAILED")).isNotEmpty();
    assertThat(usesOf(DirectorySyncOutcome.class.getName(), "NO_SUCH_VALUE")).isEmpty();
  }

  /** Where the main code names {@code type.value}, outside the file that declares the type. */
  static List<String> usesOf(String type, String value) throws IOException {
    String binaryName = type.substring(type.lastIndexOf('.') + 1);
    String simpleName = binaryName.substring(binaryName.lastIndexOf('$') + 1);
    String declaringFile = binaryName.split("\\$")[0] + ".java";
    Pattern use = Pattern.compile("\\b" + Pattern.quote(simpleName + "." + value) + "\\b");
    List<String> uses = new ArrayList<>();
    for (Path source : sources()) {
      if (!source.getFileName().toString().equals(declaringFile)
          && use.matcher(Files.readString(source)).find()) {
        uses.add(simpleName + "." + value + " is used in " + source);
      }
    }
    return uses;
  }

  @Test
  void theScanFindsTheEnumsOfTheIssue() {
    Map<String, Persisted> discovered = discover();

    assertThat(discovered.get(AuditEventType.class.getName()).attributes())
        .contains("AuditLogEntry.eventType");
    assertThat(discovered.get(DirectorySyncOutcome.class.getName()).attributes())
        .contains("DirectorySyncStatus.lastOutcome");
  }

  /**
   * The two values that broke a running older release without any migration: written by the new
   * release, unknown to the old one. Registered as readable before they existed, they fail here.
   */
  // regression guard for #2944: DirectorySyncOutcome.FAILED and a new AuditEventType
  @Test
  void aNewValueWithoutTheExpandStepFailsTheCheck() {
    Map<String, Persisted> discovered = discover();
    Map<String, Entry> registry = new LinkedHashMap<>(load(REGISTRY));
    forget(registry, DirectorySyncOutcome.class, "FAILED");
    forget(registry, AuditEventType.class, "DATA_COLLECTION_DISCLOSURE_EXPORTED");

    assertThat(check(discovered, registry))
        .containsExactlyInAnyOrder(
            "io.opaa.api.types.DirectorySyncOutcome.FAILED is new: the previous release cannot read"
                + " a row holding it (DirectorySyncStatus.lastOutcome) - add it to pending and"
                + " write it only from the release after the one that ships it",
            "io.opaa.api.types.AuditEventType.DATA_COLLECTION_DISCLOSURE_EXPORTED is new: the"
                + " previous release cannot read a row holding it (AuditLogEntry.eventType) - add it"
                + " to pending and write it only from the release after the one that ships it");
  }

  @Test
  void aPendingValueAndAnUnchangedEnumPassAndARemovedOneFails() {
    Map<String, Persisted> discovered =
        Map.of("x.Kind", new Persisted(Set.of("Row.kind"), Set.of("OLD", "NEW")));

    assertThat(
            check(
                discovered,
                Map.of(
                    "x.Kind",
                    new Entry(Set.of("Row.kind"), Assessment.STATE, Set.of("OLD"), Set.of("NEW")))))
        .isEmpty();
    assertThat(
            check(
                discovered,
                Map.of(
                    "x.Kind",
                    new Entry(
                        Set.of("Row.kind"),
                        Assessment.STATE,
                        Set.of("OLD", "NEW", "GONE"),
                        Set.of()))))
        .containsExactly(
            "x.Kind.GONE is gone: rows may still hold it and this release cannot read them - a"
                + " contract migration rewrites them first, then the value leaves the registry");
  }

  /** The registry's problems with {@code discovered}: new and gone types, attributes and values. */
  static List<String> check(Map<String, Persisted> discovered, Map<String, Entry> registry) {
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, Persisted> found : discovered.entrySet()) {
      Entry entry = registry.get(found.getKey());
      if (entry == null) {
        problems.add(found.getKey() + " is persisted but not registered");
        continue;
      }
      if (!entry.attributes().equals(found.getValue().attributes())) {
        problems.add(
            found.getKey()
                + " is persisted at "
                + new TreeSet<>(found.getValue().attributes())
                + ", registered at "
                + new TreeSet<>(entry.attributes()));
      }
      for (String value : new TreeSet<>(found.getValue().values())) {
        if (!entry.readable().contains(value) && !entry.pending().contains(value)) {
          problems.add(
              found.getKey()
                  + "."
                  + value
                  + " is new: the previous release cannot read a row holding it ("
                  + String.join(", ", new TreeSet<>(found.getValue().attributes()))
                  + ") - add it to pending and write it only from the release after the one that"
                  + " ships it");
        }
      }
      Set<String> registered = new TreeSet<>(entry.readable());
      registered.addAll(entry.pending());
      for (String value : registered) {
        if (!found.getValue().values().contains(value)) {
          problems.add(
              found.getKey()
                  + "."
                  + value
                  + " is gone: rows may still hold it and this release cannot read them - a"
                  + " contract migration rewrites them first, then the value leaves the registry");
        }
      }
    }
    for (String type : registry.keySet()) {
      if (!discovered.containsKey(type)) {
        problems.add(type + " is registered but no longer persisted");
      }
    }
    return problems;
  }

  /** Every enum a field of an entity, embeddable or mapped superclass of {@code io.opaa} stores. */
  static Map<String, Persisted> discover() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
    scanner.addIncludeFilter(new AnnotationTypeFilter(Embeddable.class));
    scanner.addIncludeFilter(new AnnotationTypeFilter(MappedSuperclass.class));
    Map<String, Set<String>> attributes = new TreeMap<>();
    Map<String, Set<String>> values = new TreeMap<>();
    for (BeanDefinition candidate : scanner.findCandidateComponents("io.opaa")) {
      Class<?> type = loadClass(candidate.getBeanClassName());
      for (Field field : type.getDeclaredFields()) {
        Class<?> stored = storedEnum(field);
        if (stored == null) {
          continue;
        }
        attributes
            .computeIfAbsent(stored.getName(), name -> new TreeSet<>())
            .add(type.getSimpleName() + "." + field.getName());
        values.put(
            stored.getName(),
            Arrays.stream(stored.getEnumConstants())
                .map(constant -> ((Enum<?>) constant).name())
                .collect(Collectors.toCollection(TreeSet::new)));
      }
    }
    Map<String, Persisted> persisted = new TreeMap<>();
    attributes.forEach((name, at) -> persisted.put(name, new Persisted(at, values.get(name))));
    return persisted;
  }

  private static Class<?> storedEnum(Field field) {
    if (Modifier.isStatic(field.getModifiers())
        || Modifier.isTransient(field.getModifiers())
        || field.isAnnotationPresent(Transient.class)) {
      return null;
    }
    if (field.getType().isEnum()) {
      return field.getType();
    }
    if (field.isAnnotationPresent(ElementCollection.class)
        && field.getGenericType() instanceof ParameterizedType parameterized) {
      for (Type argument : parameterized.getActualTypeArguments()) {
        if (argument instanceof Class<?> element && element.isEnum()) {
          return element;
        }
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Entry> load(String resource) {
    try (InputStream in =
        PersistedEnumRegistryTest.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException(resource + " is missing");
      }
      Map<String, Map<String, Object>> raw = new Yaml().load(in);
      Map<String, Entry> registry = new LinkedHashMap<>();
      raw.forEach(
          (type, entry) ->
              registry.put(
                  type,
                  new Entry(
                      strings(entry.get("attributes")),
                      Assessment.valueOf(String.valueOf(entry.get("assessment"))),
                      strings(entry.get("readable")),
                      strings(entry.get("pending")))));
      return registry;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Set<String> strings(Object list) {
    Set<String> strings = new LinkedHashSet<>();
    if (list instanceof List<?> items) {
      items.forEach(item -> strings.add(String.valueOf(item)));
    }
    return strings;
  }

  private static void forget(Map<String, Entry> registry, Class<?> type, String value) {
    Entry entry = registry.get(type.getName());
    Set<String> readable = new LinkedHashSet<>(entry.readable());
    readable.remove(value);
    registry.put(
        type.getName(),
        new Entry(entry.attributes(), entry.assessment(), readable, entry.pending()));
  }

  private static Class<?> loadClass(String className) {
    try {
      return Class.forName(className, false, PersistedEnumRegistryTest.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<Path> sources() throws IOException {
    List<Path> sources = new ArrayList<>();
    for (Path root : MAIN_SOURCES) {
      try (Stream<Path> files = Files.walk(root)) {
        files.filter(file -> file.toString().endsWith(".java")).forEach(sources::add);
      }
    }
    return sources;
  }

  /**
   * The problems, and for an unregistered type the entry to start from: a newly persisted enum
   * lands in a column the previous release does not map, so all its values are readable.
   */
  private static String report(List<String> problems) {
    StringBuilder message =
        new StringBuilder("The registry ")
            .append(REGISTRY)
            .append(" does not match the persisted enums (backend/docs/migrationen.md,")
            .append(" \"Enum-Werte\"):\n");
    problems.forEach(problem -> message.append("- ").append(problem).append('\n'));
    Map<String, Persisted> discovered = discover();
    for (String problem : problems) {
      if (problem.endsWith(" is persisted but not registered")) {
        String type = problem.substring(0, problem.indexOf(' '));
        Persisted persisted = discovered.get(type);
        message
            .append(type)
            .append(":\n  attributes: ")
            .append(new ArrayList<>(persisted.attributes()))
            .append("\n  assessment: SECURITY | AUDIT | STATE\n  readable: ")
            .append(new ArrayList<>(persisted.values()))
            .append("\n  pending: []\n");
      }
    }
    return message.toString();
  }
}
