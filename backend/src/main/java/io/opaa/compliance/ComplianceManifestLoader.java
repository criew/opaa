package io.opaa.compliance;

import io.opaa.compliance.ComplianceManifest.AiFunctionEntry;
import io.opaa.compliance.ComplianceManifest.AuditEventEntry;
import io.opaa.compliance.ComplianceManifest.DataEntry;
import io.opaa.compliance.ComplianceManifest.DataFlowEntry;
import io.opaa.compliance.ComplianceManifest.NonPersonalTable;
import io.opaa.compliance.ComplianceManifest.PromptEntry;
import io.opaa.compliance.ComplianceManifest.RoleEntry;
import io.opaa.compliance.ComplianceManifest.SettingEntry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the {@link ComplianceManifest} from the classpath and rejects it as a whole, with every
 * violation named, when an entry misses a mandatory field, carries an unknown key, repeats an id of
 * its kind, names a retention setting no {@code settings} entry describes or holds an unquoted flow
 * list value with unbalanced parentheses.
 *
 * <p>An entry is the {@code defaults} of its section in the same file, overridden key by key by the
 * entry itself; the mandatory check runs on that result.
 */
public final class ComplianceManifestLoader {

  /** Where the area files lie; one file per area, the file name is the area. */
  public static final String LOCATION = "classpath*:compliance/manifest/*.yaml";

  private static final JsonMapper STRICT =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  /** The entry sections of an area file, in the order violations are reported. */
  private static final Map<String, Class<?>> SECTIONS = sections();

  private static final Set<String> FILE_KEYS = fileKeys();

  private ComplianceManifestLoader() {}

  /** The manifest delivered with this build. */
  public static ComplianceManifest load() {
    return load(LOCATION);
  }

  /**
   * The manifest of the area files matching {@code locationPattern}.
   *
   * @throws ComplianceManifestException naming every violation, when there is at least one
   */
  public static ComplianceManifest load(String locationPattern) {
    Resource[] resources = resources(locationPattern);
    List<String> violations = new ArrayList<>();
    Map<String, String> areas = new TreeMap<>();
    Map<String, List<Object>> sections = new LinkedHashMap<>();
    SECTIONS.keySet().forEach(section -> sections.put(section, new ArrayList<>()));
    List<NonPersonalTable> nonPersonalTables = new ArrayList<>();

    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      Validator validator = factory.getValidator();
      for (Resource resource : resources) {
        String area = areaOf(resource);
        Map<String, Object> file = parse(resource, area, violations);
        if (file == null) {
          continue;
        }
        readArea(area, file, validator, violations, areas, sections, nonPersonalTables);
      }
    }

    ComplianceManifest manifest =
        new ComplianceManifest(
            areas,
            typed(sections, "data"),
            nonPersonalTables,
            typed(sections, "auditEvents"),
            typed(sections, "roles"),
            typed(sections, "capabilities"),
            typed(sections, "settings"),
            typed(sections, "prompts"),
            typed(sections, "dataFlows"),
            typed(sections, "aiFunctions"));
    checkAcrossAreas(manifest, violations);
    checkPromptReferences(manifest, violations);
    if (!violations.isEmpty()) {
      throw new ComplianceManifestException(violations);
    }
    return manifest;
  }

  private static Resource[] resources(String locationPattern) {
    try {
      Resource[] found =
          new PathMatchingResourcePatternResolver(ComplianceManifestLoader.class.getClassLoader())
              .getResources(locationPattern);
      if (found.length == 0) {
        throw new ComplianceManifestException(
            List.of("no manifest file found at " + locationPattern));
      }
      Arrays.sort(found, Comparator.comparing(ComplianceManifestLoader::areaOf));
      return found;
    } catch (IOException e) {
      throw new IllegalStateException("cannot list manifest files at " + locationPattern, e);
    }
  }

  private static String areaOf(Resource resource) {
    String name = resource.getFilename();
    return name == null ? "" : name.substring(0, name.length() - ".yaml".length());
  }

  private static Map<String, Object> parse(
      Resource resource, String area, List<String> violations) {
    try (InputStream in = resource.getInputStream()) {
      String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      Yaml yaml = new Yaml(new SafeConstructor(options));
      Object root = yaml.load(content);
      if (root instanceof Map<?, ?> map) {
        checkSplitFlowValues(yaml.compose(new StringReader(content)), area, violations);
        return stringKeys(map);
      }
      violations.add(area + ": the file must hold a mapping");
    } catch (IOException | RuntimeException e) {
      violations.add(area + ": unreadable YAML (" + e.getMessage() + ")");
    }
    return null;
  }

  /**
   * An unquoted value of a flow list {@code [a (b, c), d]} with unbalanced parentheses: the comma
   * inside the parentheses has split one value into two, silently. Block lists and quoted values
   * cannot split and are not checked.
   */
  private static void checkSplitFlowValues(Node node, String area, List<String> violations) {
    switch (node) {
      case MappingNode mapping ->
          mapping
              .getValue()
              .forEach(tuple -> checkSplitFlowValues(tuple.getValueNode(), area, violations));
      case SequenceNode sequence -> {
        boolean flow = sequence.getFlowStyle() == DumperOptions.FlowStyle.FLOW;
        for (Node value : sequence.getValue()) {
          if (flow
              && value instanceof ScalarNode scalar
              && scalar.getScalarStyle() == DumperOptions.ScalarStyle.PLAIN
              && unbalanced(scalar.getValue())) {
            violations.add(
                area
                    + ": flow list value '"
                    + scalar.getValue()
                    + "' has unbalanced parentheses; a comma inside them splits the value, quote"
                    + " it");
          } else {
            checkSplitFlowValues(value, area, violations);
          }
        }
      }
      default -> {}
    }
  }

  private static boolean unbalanced(String text) {
    return text.chars().filter(c -> c == '(').count() != text.chars().filter(c -> c == ')').count();
  }

  private static void readArea(
      String area,
      Map<String, Object> file,
      Validator validator,
      List<String> violations,
      Map<String, String> areas,
      Map<String, List<Object>> sections,
      List<NonPersonalTable> nonPersonalTables) {
    for (String key : file.keySet()) {
      if (!FILE_KEYS.contains(key)) {
        violations.add(area + ": unknown key '" + key + "'");
      }
    }
    if (!(file.get("title") instanceof String title) || title.isBlank()) {
      violations.add(area + ": mandatory field 'title' is missing or empty");
    } else {
      areas.put(area, title);
    }

    Map<String, Object> defaults = mapOrEmpty(file.get("defaults"), area, "defaults", violations);
    for (String section : defaults.keySet()) {
      if (!SECTIONS.containsKey(section)) {
        violations.add(area + ": defaults name the unknown section '" + section + "'");
      }
    }

    for (Map.Entry<String, Class<?>> section : SECTIONS.entrySet()) {
      String name = section.getKey();
      Map<String, Object> sectionDefaults =
          mapOrEmpty(defaults.get(name), area, "defaults." + name, violations);
      List<Object> entries = listOrEmpty(file.get(name), area, name, violations);
      for (int i = 0; i < entries.size(); i++) {
        String where = area + ": " + name + "[" + i + "]";
        if (!(entries.get(i) instanceof Map<?, ?> raw)) {
          violations.add(where + ": an entry must be a mapping");
          continue;
        }
        Map<String, Object> merged = new LinkedHashMap<>(sectionDefaults);
        merged.putAll(stringKeys(raw));
        if (merged.containsKey("area")) {
          violations.add(where + ": 'area' is set by the file name, not in the file");
        }
        merged.put("area", area);
        Object entry = convert(merged, section.getValue(), where, violations);
        if (entry != null) {
          String label = area + ": " + name + " '" + idOf(entry) + "'";
          validate(validator, entry, label, violations);
          sections.get(name).add(entry);
        }
      }
    }

    for (Object table :
        listOrEmpty(file.get("nonPersonalTables"), area, "nonPersonalTables", violations)) {
      if (table instanceof String name && !name.isBlank()) {
        nonPersonalTables.add(new NonPersonalTable(area, name));
      } else {
        violations.add(area + ": nonPersonalTables holds a value that is no table name");
      }
    }
  }

  private static Object convert(
      Map<String, Object> merged, Class<?> type, String where, List<String> violations) {
    try {
      return STRICT.convertValue(merged, type);
    } catch (JacksonException | IllegalArgumentException e) {
      violations.add(where + ": " + e.getMessage().lines().findFirst().orElse(""));
      return null;
    }
  }

  private static void validate(
      Validator validator, Object entry, String label, List<String> violations) {
    for (ConstraintViolation<Object> violation : validator.validate(entry)) {
      String field = violation.getPropertyPath().toString();
      String kind =
          violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
      violations.add(
          label
              + ": "
              + switch (kind) {
                case "NotBlank", "NotNull", "NotEmpty" ->
                    "mandatory field '" + field + "' is missing or empty";
                case "AssertTrue" -> "rule '" + field + "' is not met";
                default -> "field '" + field + "' violates @" + kind;
              });
    }
  }

  /**
   * Ids unique per kind, every retention setting described by a settings entry and every prompt an
   * AI function names described by a prompts entry.
   */
  private static void checkAcrossAreas(ComplianceManifest manifest, List<String> violations) {
    duplicates(manifest.data(), DataEntry::id, "data", violations);
    duplicates(manifest.auditEvents(), AuditEventEntry::id, "auditEvents", violations);
    duplicates(manifest.roles(), RoleEntry::id, "roles", violations);
    duplicates(manifest.capabilities(), RoleEntry::id, "capabilities", violations);
    duplicates(manifest.settings(), SettingEntry::key, "settings", violations);
    duplicates(manifest.prompts(), PromptEntry::id, "prompts", violations);
    duplicates(manifest.dataFlows(), DataFlowEntry::id, "dataFlows", violations);
    duplicates(manifest.aiFunctions(), AiFunctionEntry::id, "aiFunctions", violations);
    duplicates(
        manifest.nonPersonalTables(), NonPersonalTable::table, "nonPersonalTables", violations);

    Set<String> settingKeys = new HashSet<>();
    manifest.settings().forEach(setting -> settingKeys.add(setting.key()));
    List<ComplianceManifest.DataFacts> facts = new ArrayList<>(manifest.data());
    facts.addAll(manifest.auditEvents());
    for (ComplianceManifest.DataFacts entry : facts) {
      if (entry.retention() == null || entry.retention().settings() == null) {
        continue;
      }
      for (String key : entry.retention().settings()) {
        if (!settingKeys.contains(key)) {
          violations.add(
              entry.area()
                  + ": '"
                  + entry.id()
                  + "' names the retention setting '"
                  + key
                  + "', which no settings entry describes");
        }
      }
    }
  }

  private static void checkPromptReferences(ComplianceManifest manifest, List<String> violations) {
    Set<String> promptIds = new HashSet<>();
    manifest.prompts().forEach(prompt -> promptIds.add(prompt.id()));
    for (AiFunctionEntry function : manifest.aiFunctions()) {
      if (function.prompts() == null) {
        continue;
      }
      for (String prompt : function.prompts()) {
        if (!promptIds.contains(prompt)) {
          violations.add(
              function.area()
                  + ": AI function '"
                  + function.id()
                  + "' names the prompt '"
                  + prompt
                  + "', which no prompts entry describes");
        }
      }
    }
  }

  private static <T> void duplicates(
      List<T> entries, Function<T, String> id, String kind, List<String> violations) {
    Set<String> seen = new HashSet<>();
    for (T entry : entries) {
      String value = id.apply(entry);
      if (value != null && !seen.add(value)) {
        violations.add(kind + ": '" + value + "' appears more than once");
      }
    }
  }

  private static String idOf(Object entry) {
    return switch (entry) {
      case DataEntry e -> e.id();
      case AuditEventEntry e -> e.id();
      case RoleEntry e -> e.id();
      case SettingEntry e -> e.key();
      case PromptEntry e -> e.id();
      case DataFlowEntry e -> e.id();
      case AiFunctionEntry e -> e.id();
      default -> "?";
    };
  }

  @SuppressWarnings("unchecked")
  private static <T> List<T> typed(Map<String, List<Object>> sections, String name) {
    return (List<T>) (List<?>) sections.get(name);
  }

  private static Map<String, Object> mapOrEmpty(
      Object value, String area, String key, List<String> violations) {
    if (value == null) {
      return Map.of();
    }
    if (value instanceof Map<?, ?> map) {
      return stringKeys(map);
    }
    violations.add(area + ": '" + key + "' must be a mapping");
    return Map.of();
  }

  @SuppressWarnings("unchecked")
  private static List<Object> listOrEmpty(
      Object value, String area, String key, List<String> violations) {
    if (value == null) {
      return List.of();
    }
    if (value instanceof List<?> list) {
      return (List<Object>) list;
    }
    violations.add(area + ": '" + key + "' must be a list");
    return List.of();
  }

  private static Map<String, Object> stringKeys(Map<?, ?> map) {
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((key, value) -> result.put(String.valueOf(key), value));
    return result;
  }

  private static Map<String, Class<?>> sections() {
    Map<String, Class<?>> sections = new LinkedHashMap<>();
    sections.put("data", DataEntry.class);
    sections.put("auditEvents", AuditEventEntry.class);
    sections.put("roles", RoleEntry.class);
    sections.put("capabilities", RoleEntry.class);
    sections.put("settings", SettingEntry.class);
    sections.put("prompts", PromptEntry.class);
    sections.put("dataFlows", DataFlowEntry.class);
    sections.put("aiFunctions", AiFunctionEntry.class);
    return sections;
  }

  private static Set<String> fileKeys() {
    Set<String> keys = new HashSet<>(SECTIONS.keySet());
    keys.addAll(List.of("title", "defaults", "nonPersonalTables"));
    return Set.copyOf(keys);
  }
}
