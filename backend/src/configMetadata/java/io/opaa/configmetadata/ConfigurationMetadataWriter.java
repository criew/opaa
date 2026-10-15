package io.opaa.configmetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the configuration metadata the backend ships: what {@code
 * spring-boot-configuration-processor} found on the {@code @ConfigurationProperties} types, plus
 * {@code additional-spring-configuration-metadata.json} for keys read without such a type, with the
 * default of every key that the base document of {@code application.yml} sets.
 *
 * <p>The default is the value with every placeholder resolved without any environment, as a fresh
 * installation sees it. It replaces the processor's own guess, which for a record is the zero of a
 * primitive rather than the value the application starts with. An additional entry replaces one of
 * the processor with the same name and source type; every section is sorted by name.
 */
public final class ConfigurationMetadataWriter {

  private static final String PROFILE_KEY = "spring.config.activate.on-profile";

  private ConfigurationMetadataWriter() {}

  /** Arguments: processor metadata, additional metadata, application.yml, output file. */
  public static void main(String[] args) throws IOException {
    if (args.length != 4) {
      throw new IllegalArgumentException(
          "usage: ConfigurationMetadataWriter <processor.json> <additional.json>"
              + " <application.yml> <output.json>");
    }
    JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    ObjectNode metadata = (ObjectNode) json.readTree(Path.of(args[0]).toFile());
    JsonNode additional = json.readTree(Path.of(args[1]).toFile());
    Map<String, String> defaults = baseDefaults(Path.of(args[2]));
    for (String section : List.of("groups", "properties", "hints")) {
      Map<String, ObjectNode> byName = new LinkedHashMap<>();
      metadata.path(section).forEach(node -> byName.put(identity(node), (ObjectNode) node));
      additional.path(section).forEach(node -> byName.put(identity(node), (ObjectNode) node));
      List<ObjectNode> items = new ArrayList<>(byName.values());
      items.sort(Comparator.comparing(ConfigurationMetadataWriter::name));
      if (section.equals("properties")) {
        for (ObjectNode property : items) {
          String value = defaults.get(name(property));
          if (value != null) {
            property.put("defaultValue", value);
          }
        }
      }
      ArrayNode target = metadata.putArray(section);
      items.forEach(target::add);
    }

    Path out = Path.of(args[3]);
    Files.createDirectories(out.getParent());
    json.writeValue(out.toFile(), metadata);
  }

  private static String name(JsonNode node) {
    return node.path("name").asString();
  }

  /** Two groups may share a prefix; an item is the same only with the same source type. */
  private static String identity(JsonNode node) {
    return name(node) + "|" + node.path("sourceType").asString("");
  }

  /**
   * Every key of the one document without a profile, resolved as a fresh installation reads it.
   *
   * @throws IllegalStateException when there is not exactly one such document, or when a value
   *     names a placeholder that neither has a default nor is a key of that document
   */
  static Map<String, String> baseDefaults(Path applicationYml) throws IOException {
    List<EnumerablePropertySource<?>> bases =
        new YamlPropertySourceLoader()
            .load("application.yml", new FileSystemResource(applicationYml)).stream()
                .filter(document -> document.getProperty(PROFILE_KEY) == null)
                .<EnumerablePropertySource<?>>map(
                    document -> (EnumerablePropertySource<?>) document)
                .toList();
    if (bases.size() != 1) {
      throw new IllegalStateException(
          applicationYml
              + " must hold exactly one document without a profile, found "
              + bases.size());
    }
    EnumerablePropertySource<?> base = bases.getFirst();
    MutablePropertySources sources = new MutablePropertySources();
    sources.addFirst(base);
    PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);
    Map<String, String> defaults = new LinkedHashMap<>();
    for (String name : base.getPropertyNames()) {
      try {
        defaults.put(name, resolver.getProperty(name));
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException(
            name
                + " in "
                + applicationYml
                + " has no default: write the placeholder as ${VARIABLE:<default>} ("
                + e.getMessage()
                + ")",
            e);
      }
    }
    return defaults;
  }
}
