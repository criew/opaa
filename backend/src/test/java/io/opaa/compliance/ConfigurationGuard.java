package io.opaa.compliance;

import io.opaa.compliance.ComplianceManifest.HelmValue;
import io.opaa.compliance.ComplianceManifest.SettingEntry;
import io.opaa.compliance.ComplianceManifest.SettingSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The configuration side of the completeness guard (ADR-0052, Entscheidung 9): every {@code opaa.*}
 * key is described and classified by {@link DataImpact} or {@link NoDataImpact}; every key with
 * data impact has a {@code settings} entry whose default is the one {@code application.yml}
 * delivers; and every key the Helm chart sets names the chart's value and its default. Each gap is
 * one message naming the key, the file and the YAML or Java to add.
 */
final class ConfigurationGuard {

  /** Where an entry for a key without a better area goes. */
  static final String CONFIGURATION_AREA = "configuration";

  /** Profiles that never run in production; their overrides need no note. */
  static final Set<String> NON_PRODUCTION_PROFILES = Set.of("dev");

  private static final String PROFILE_KEY = "spring.config.activate.on-profile";
  private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{(OPAA_[A-Z0-9_]+)");
  private static final Pattern ENV_ASSIGNMENT = Pattern.compile("(?m)^\\s*(OPAA_[A-Z0-9_]+)\\s*:");
  private static final String FEATURE_RANGE = "OPAA_FEATURES_{{";
  private static final Pattern SPRING_AI_MODEL_KEY =
      Pattern.compile("spring\\.ai\\..*\\.(model|base-url)");

  private ConfigurationGuard() {}

  /** A key of the configuration metadata. */
  record Key(String name, String sourceType, String description, String defaultValue) {}

  /** The verdict of the markers on a key. */
  enum Impact {
    DATA,
    NONE
  }

  // ---------------------------------------------------------------- reading the delivered state

  /** The metadata the build ships, {@code META-INF/spring-configuration-metadata.json}. */
  static List<Key> deliveredMetadata() {
    try (InputStream in =
        new ClassPathResource("META-INF/spring-configuration-metadata.json").getInputStream()) {
      JsonNode root = JsonMapper.builder().build().readTree(in);
      List<Key> keys = new ArrayList<>();
      for (JsonNode property : root.path("properties")) {
        keys.add(
            new Key(
                property.path("name").asString(),
                text(property, "sourceType"),
                text(property, "description"),
                text(property, "defaultValue")));
      }
      return keys;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  /**
   * The documents of an {@code application.yml}: each key resolved without any environment, the
   * profile documents on top of the base document, and the raw value of every key.
   */
  record ApplicationYaml(
      Map<String, String> base,
      Map<String, Map<String, String>> profiles,
      Map<String, String> raw) {

    static ApplicationYaml delivered() {
      return read(new ClassPathResource("application.yml"));
    }

    static ApplicationYaml read(Resource resource) {
      List<PropertySource<?>> documents;
      try {
        documents = new YamlPropertySourceLoader().load(resource.getFilename(), resource);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      EnumerablePropertySource<?> base = null;
      Map<String, EnumerablePropertySource<?>> profiles = new LinkedHashMap<>();
      Map<String, String> raw = new LinkedHashMap<>();
      for (PropertySource<?> document : documents) {
        EnumerablePropertySource<?> source = (EnumerablePropertySource<?>) document;
        Object profile = source.getProperty(PROFILE_KEY);
        if (profile == null) {
          base = source;
        } else {
          profiles.put(profile.toString(), source);
        }
        for (String name : source.getPropertyNames()) {
          raw.putIfAbsent(name, String.valueOf(source.getProperty(name)));
        }
      }
      if (base == null) {
        throw new IllegalStateException(resource + " has no document without a profile");
      }
      Map<String, Map<String, String>> resolvedProfiles = new LinkedHashMap<>();
      for (Map.Entry<String, EnumerablePropertySource<?>> profile : profiles.entrySet()) {
        resolvedProfiles.put(profile.getKey(), resolve(profile.getValue(), base));
      }
      return new ApplicationYaml(resolve(base, null), resolvedProfiles, raw);
    }

    private static Map<String, String> resolve(
        EnumerablePropertySource<?> document, EnumerablePropertySource<?> fallback) {
      MutablePropertySources sources = new MutablePropertySources();
      sources.addLast(document);
      if (fallback != null) {
        sources.addLast(fallback);
      }
      PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);
      Map<String, String> values = new LinkedHashMap<>();
      for (String name : document.getPropertyNames()) {
        if (!name.equals(PROFILE_KEY)) {
          values.put(name, resolver.getProperty(name));
        }
      }
      return values;
    }

    /** Every key any document sets, list indexes cut off ({@code a.b[0].c} is {@code a.b}). */
    Set<String> keys() {
      Set<String> keys = new TreeSet<>();
      raw.keySet().forEach(name -> keys.add(name.replaceFirst("\\[.*$", "")));
      return keys;
    }

    /** The environment variables the documents read for {@code key}. */
    Set<String> environmentVariables(String key) {
      Set<String> names = new LinkedHashSet<>();
      String value = raw.get(key);
      if (value != null) {
        Matcher matcher = ENV_PLACEHOLDER.matcher(value);
        while (matcher.find()) {
          names.add(matcher.group(1));
        }
      }
      return names;
    }

    Set<String> allEnvironmentVariables() {
      Set<String> names = new TreeSet<>();
      raw.keySet().forEach(key -> names.addAll(environmentVariables(key)));
      return names;
    }
  }

  /** The backend's environment as the Helm chart renders it, and the chart's value defaults. */
  record HelmChart(Set<String> configured, Set<String> secret, Map<String, Object> values) {

    static HelmChart delivered() {
      return read(Path.of("../deploy/helm/opaa"));
    }

    static HelmChart read(Path chart) {
      Map<String, Object> values = yaml(chart.resolve("values.yaml"));
      String configMap = text(chart.resolve("templates/backend-configmap.yaml"));
      Set<String> configured = assigned(configMap);
      if (configMap.contains(FEATURE_RANGE) && values.get("features") instanceof Map<?, ?> flags) {
        for (Object flag : flags.keySet()) {
          configured.add("OPAA_FEATURES_" + snake(flag.toString()) + "_ENABLED");
        }
      }
      return new HelmChart(
          configured, assigned(text(chart.resolve("templates/secret.yaml"))), values);
    }

    /** The default at a dotted path of {@code values.yaml}, empty when the path does not exist. */
    Optional<String> defaultAt(String path) {
      Object node = values;
      for (String segment : path.split("\\.")) {
        if (!(node instanceof Map<?, ?> map) || !map.containsKey(segment)) {
          return Optional.empty();
        }
        node = map.get(segment);
      }
      return Optional.of(
          switch (node) {
            case null -> "";
            case Collection<?> list ->
                list.stream().map(String::valueOf).collect(Collectors.joining(","));
            default -> String.valueOf(node);
          });
    }

    private static Set<String> assigned(String template) {
      Set<String> names = new TreeSet<>();
      Matcher matcher = ENV_ASSIGNMENT.matcher(template);
      while (matcher.find()) {
        names.add(matcher.group(1));
      }
      return names;
    }

    private static String snake(String camel) {
      return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static String text(Path file) {
      try {
        return Files.readString(file);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    private static Map<String, Object> yaml(Path file) {
      return new Yaml(new SafeConstructor(new LoaderOptions())).load(text(file));
    }
  }

  // ---------------------------------------------------------------- classification

  /**
   * Whether a key needs a marker of its own rather than the blanket {@link NoDataImpact} of its
   * {@code @ConfigurationProperties} type: a switch, a model, an address or a period, judged by the
   * last segment of the key.
   */
  static boolean needsOwnDecision(String key) {
    String last = key.substring(key.lastIndexOf('.') + 1);
    return last.equals("enabled")
        || last.endsWith("-enabled")
        || last.equals("model")
        || last.endsWith("-model")
        || last.endsWith("url")
        || last.endsWith("uri")
        || last.contains("endpoint")
        || last.endsWith("-days")
        || last.endsWith("-months")
        || last.endsWith("ttl")
        || last.contains("lifetime")
        || last.contains("retention");
  }

  /**
   * The marker that decides {@code key}: one on its component, else one on its source type or an
   * enclosing type. Empty, with the reason in {@code problems}, when none decides it.
   */
  static Optional<Impact> impactOf(Key key, List<String> problems) {
    if (key.sourceType() == null) {
      problems.add(
          key.name()
              + ": no sourceType in the configuration metadata - name the class that reads it in"
              + " src/main/resources/META-INF/additional-spring-configuration-metadata.json");
      return Optional.empty();
    }
    Class<?> type;
    try {
      type = Class.forName(key.sourceType());
    } catch (ClassNotFoundException e) {
      problems.add(key.name() + ": sourceType " + key.sourceType() + " does not exist");
      return Optional.empty();
    }
    String segment = key.name().substring(key.name().lastIndexOf('.') + 1);
    Optional<AnnotatedElement> component = component(type, camel(segment));
    Marker own = component.map(ConfigurationGuard::marker).orElse(Marker.ABSENT);
    if (own == Marker.BOTH) {
      problems.add(key.name() + ": both @DataImpact and @NoDataImpact on " + component.get());
      return Optional.empty();
    }
    if (own != Marker.ABSENT) {
      return Optional.of(own.impact);
    }
    boolean propertiesType = false;
    for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
      propertiesType |= current.isAnnotationPresent(ConfigurationProperties.class);
    }
    for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
      Marker blanket = marker(current);
      if (blanket == Marker.ABSENT) {
        continue;
      }
      if (blanket == Marker.BOTH) {
        problems.add(key.name() + ": both @DataImpact and @NoDataImpact on " + current);
        return Optional.empty();
      }
      if (blanket == Marker.NONE && propertiesType && needsOwnDecision(key.name())) {
        problems.add(
            key.name()
                + ": a switch, model, address or period is decided on its own - annotate the"
                + " component '"
                + camel(segment)
                + "' of "
                + type.getName()
                + " with @DataImpact or @NoDataImpact(\"<why not>\")");
        return Optional.empty();
      }
      return Optional.of(blanket.impact);
    }
    problems.add(
        key.name()
            + ": not classified - annotate the component '"
            + camel(segment)
            + "' of "
            + type.getName()
            + " with @DataImpact or @NoDataImpact(\"<why not>\"), or the type with"
            + " @NoDataImpact(\"<why none of its keys>\")");
    return Optional.empty();
  }

  /** The markers present on one element. */
  private enum Marker {
    DATA(Impact.DATA),
    NONE(Impact.NONE),
    BOTH(null),
    ABSENT(null);

    private final Impact impact;

    Marker(Impact impact) {
      this.impact = impact;
    }
  }

  private static Marker marker(AnnotatedElement element) {
    boolean data = element.isAnnotationPresent(DataImpact.class);
    boolean none = element.isAnnotationPresent(NoDataImpact.class);
    return data && none ? Marker.BOTH : data ? Marker.DATA : none ? Marker.NONE : Marker.ABSENT;
  }

  private static Optional<AnnotatedElement> component(Class<?> type, String name) {
    if (type.isRecord()) {
      return Arrays.stream(type.getRecordComponents())
          .filter(component -> component.getName().equals(name))
          .map(RecordComponent.class::cast)
          .map(AnnotatedElement.class::cast)
          .findFirst();
    }
    return Arrays.stream(type.getDeclaredFields())
        .filter(field -> field.getName().equals(name))
        .map(AnnotatedElement.class::cast)
        .findFirst();
  }

  private static String camel(String kebab) {
    StringBuilder out = new StringBuilder();
    for (String part : kebab.split("-")) {
      out.append(out.isEmpty() ? part : Character.toUpperCase(part.charAt(0)) + part.substring(1));
    }
    return out.toString();
  }

  // ---------------------------------------------------------------- checks

  /**
   * Every {@code opaa.*} key any document of {@code application.yml} sets is in the metadata with a
   * description, and the shipped default is the one the base document delivers.
   */
  static List<String> metadataGaps(List<Key> keys, ApplicationYaml yaml) {
    List<String> gaps = new ArrayList<>();
    Map<String, Key> byName = byName(keys);
    for (String name : yaml.keys()) {
      if (name.startsWith("opaa.") && !byName.containsKey(name)) {
        gaps.add(
            name
                + ": set in application.yml but not in the configuration metadata - bind it"
                + " through a @ConfigurationProperties record, or describe it in"
                + " src/main/resources/META-INF/additional-spring-configuration-metadata.json:\n"
                + "    {\"name\": \""
                + name
                + "\", \"type\": \"<java type>\", \"description\": \"<what it does>\","
                + " \"sourceType\": \"<class that reads it>\"}");
      }
    }
    for (Key key : keys) {
      if (!key.name().startsWith("opaa.")) {
        continue;
      }
      if (key.description() == null || key.description().isBlank()) {
        gaps.add(
            key.name()
                + ": no description - document the component in the Javadoc of "
                + key.sourceType()
                + " (@param)");
      }
      String delivered = yaml.base().get(key.name());
      if (delivered != null && !delivered.equals(key.defaultValue())) {
        gaps.add(
            key.name()
                + ": the metadata says default \""
                + key.defaultValue()
                + "\", application.yml delivers \""
                + delivered
                + "\" - run ./gradlew writeConfigurationMetadata");
      }
    }
    return gaps;
  }

  /** Every {@code opaa.*} key of the metadata is classified, without contradiction. */
  static List<String> classificationGaps(List<Key> keys) {
    List<String> gaps = new ArrayList<>();
    for (Key key : keys) {
      if (key.name().startsWith("opaa.")) {
        impactOf(key, gaps);
      }
    }
    return gaps;
  }

  /**
   * Every key with data impact has a {@code PROPERTY} entry, written out in the base document of
   * {@code application.yml}, whose default is the delivered one; a production profile that
   * overrides it is named in the entry's notes; and no {@code PROPERTY} entry lacks its key or its
   * marker.
   */
  static List<String> settingGaps(
      List<SettingEntry> settings, List<Key> keys, ApplicationYaml yaml) {
    List<String> gaps = new ArrayList<>();
    Map<String, SettingEntry> entries = new TreeMap<>();
    settings.stream()
        .filter(entry -> entry.source() == SettingSource.PROPERTY)
        .forEach(entry -> entries.put(entry.key(), entry));
    Map<String, Key> byName = byName(keys);

    Set<String> withImpact = new TreeSet<>();
    for (Key key : keys) {
      if (key.name().startsWith("opaa.")
          && impactOf(key, new ArrayList<>()).orElse(null) == Impact.DATA) {
        withImpact.add(key.name());
      }
    }
    for (String name : yaml.base().keySet()) {
      if (SPRING_AI_MODEL_KEY.matcher(name).matches()) {
        withImpact.add(name);
      }
    }

    for (String name : withImpact) {
      SettingEntry entry = entries.get(name);
      String delivered = yaml.base().get(name);
      if (delivered == null) {
        gaps.add(
            name
                + ": has data impact but no default in the base document of application.yml -"
                + " write it out there, e.g. "
                + leaf(name)
                + ": ${OPAA_<NAME>:<default>}");
        continue;
      }
      if (entry == null) {
        gaps.add(
            name
                + ": has data impact but no settings entry - add to"
                + " compliance/manifest/"
                + CONFIGURATION_AREA
                + ".yaml (or the area of the data it bears on):\n"
                + "settings:\n"
                + "  - key: "
                + name
                + "\n    source: PROPERTY\n    defaultValue: "
                + quoted(delivered)
                + "\n    effect: <what the default does, in German>\n"
                + "    bearings: [<DATA, VISIBILITY, EVALUATION, DATA_FLOW>]");
        continue;
      }
      if (!delivered.equals(entry.defaultValue())) {
        gaps.add(
            name
                + ": application.yml now delivers "
                + quoted(delivered)
                + ", compliance/manifest/"
                + entry.area()
                + ".yaml still says "
                + quoted(entry.defaultValue())
                + " - review effect, range and notes, then set:\n    defaultValue: "
                + quoted(delivered));
      }
      for (Map.Entry<String, Map<String, String>> profile : yaml.profiles().entrySet()) {
        String value = profile.getValue().get(name);
        if (NON_PRODUCTION_PROFILES.contains(profile.getKey())
            || value == null
            || value.equals(delivered)) {
          continue;
        }
        String note = "Profil " + profile.getKey() + ": " + value;
        boolean named =
            entry.notes() != null && entry.notes().stream().anyMatch(n -> n.contains(note));
        if (!named) {
          gaps.add(
              name
                  + ": the profile '"
                  + profile.getKey()
                  + "' overrides the default - name it in the notes of its entry in"
                  + " compliance/manifest/"
                  + entry.area()
                  + ".yaml:\n    notes:\n      - \""
                  + note
                  + "\"");
        }
      }
    }

    for (SettingEntry entry : entries.values()) {
      String name = entry.key();
      if (withImpact.contains(name)) {
        continue;
      }
      if (byName.containsKey(name)) {
        gaps.add(
            name
                + ": has a settings entry in compliance/manifest/"
                + entry.area()
                + ".yaml but is not marked @DataImpact on "
                + byName.get(name).sourceType()
                + " - mark it, or remove the entry");
      } else if (!yaml.base().containsKey(name)) {
        gaps.add(
            name
                + ": the settings entry in compliance/manifest/"
                + entry.area()
                + ".yaml names a key that application.yml does not set - remove or rename it");
      }
    }
    return gaps;
  }

  /**
   * The chart sets only keys {@code application.yml} reads, and every {@code settings} entry whose
   * key the chart sets names the chart's value and its current default; no other entry names one.
   */
  static List<String> helmGaps(List<SettingEntry> settings, ApplicationYaml yaml, HelmChart chart) {
    List<String> gaps = new ArrayList<>();
    Set<String> read = yaml.allEnvironmentVariables();
    Set<String> rendered = new TreeSet<>(chart.configured());
    rendered.addAll(chart.secret());
    for (String variable : rendered) {
      if (!read.contains(variable)) {
        gaps.add(
            variable
                + ": the Helm chart sets it, but no key of application.yml reads it - rename it"
                + " in deploy/helm/opaa/templates or read it in application.yml");
      }
    }
    for (SettingEntry entry : settings) {
      if (entry.source() != SettingSource.PROPERTY) {
        continue;
      }
      Set<String> variables = yaml.environmentVariables(entry.key());
      List<String> set = variables.stream().filter(chart.configured()::contains).toList();
      HelmValue helm = entry.helm();
      String file = "compliance/manifest/" + entry.area() + ".yaml";
      if (!set.isEmpty() && helm == null) {
        gaps.add(
            entry.key()
                + ": the Helm chart sets it through "
                + set
                + " - name the value of deploy/helm/opaa/values.yaml in its entry in "
                + file
                + ":\n    helm:\n      values: <dotted path>\n      defaultValue: \"<its default>\"");
      } else if (set.isEmpty() && helm != null) {
        gaps.add(
            entry.key()
                + ": the Helm chart no longer sets it - remove 'helm' from its entry in "
                + file);
      } else if (helm != null) {
        Optional<String> chartDefault = chart.defaultAt(helm.values());
        if (chartDefault.isEmpty()) {
          gaps.add(
              entry.key()
                  + ": deploy/helm/opaa/values.yaml has no value '"
                  + helm.values()
                  + "' - correct 'helm.values' in "
                  + file);
        } else if (!chartDefault.get().equals(helm.defaultValue())) {
          gaps.add(
              entry.key()
                  + ": deploy/helm/opaa/values.yaml now defaults '"
                  + helm.values()
                  + "' to "
                  + quoted(chartDefault.get())
                  + ", "
                  + file
                  + " still says "
                  + quoted(helm.defaultValue())
                  + " - review the entry, then set:\n    helm:\n      values: "
                  + helm.values()
                  + "\n      defaultValue: "
                  + quoted(chartDefault.get()));
        }
      }
    }
    return gaps;
  }

  /** The failure message: what was checked, then one paragraph per gap. */
  static String report(String what, List<String> gaps) {
    return gaps.size()
        + " gap(s) between the configuration ("
        + what
        + ") and the compliance manifest (ADR-0052, Entscheidung 9):\n\n"
        + String.join("\n\n", gaps);
  }

  private static Map<String, Key> byName(List<Key> keys) {
    return keys.stream()
        .collect(Collectors.toMap(Key::name, Function.identity(), (a, b) -> a, TreeMap::new));
  }

  private static String leaf(String key) {
    return key.substring(key.lastIndexOf('.') + 1);
  }

  private static String quoted(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
