package io.opaa.compliance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The compliance manifest of one version in a schema-free form, so that {@link ManifestDelta} can
 * compare versions whose manifest schema differs: per section, each entry by its identifier as a
 * flat map from field path to value. A value is a {@code String} or a {@code List<String>}; a
 * nested mapping becomes {@code parent.child}, a list of tables {@code tables[<name>].<field>}.
 * Absent and {@code null} fields are left out.
 *
 * <p>Read from the area files (as in the repository, {@code defaults} merged as the {@link
 * ComplianceManifestLoader} merges them, but without validation) or from the JSON form of the fact
 * sheets ({@link FactSheetGenerator#JSON_FILE}). Both forms of one manifest yield the same
 * snapshot.
 *
 * @param origin where the manifest was read from
 * @param areas area to its title
 * @param sections section name to entry identifier to the entry's fields; sorted
 */
public record ManifestSnapshot(
    Origin origin,
    SortedMap<String, String> areas,
    SortedMap<String, SortedMap<String, SortedMap<String, Object>>> sections) {

  /** The commit of a version that has none or states none that can be trusted. */
  public static final String UNKNOWN = "unbekannt";

  private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{7,64}");

  /** Path of the area files in the repository, relative to its top level. */
  public static final String REPOSITORY_PATH = "backend/src/main/resources/compliance/manifest";

  /** The keys of an area file that are no section of entries. */
  private static final Set<String> FILE_KEYS = Set.of("title", "defaults", "nonPersonalTables");

  /** Fields naming an entry, in the order they are looked for. */
  private static final List<String> IDENTITY_FIELDS = List.of("id", "key", "table");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * The version a snapshot describes.
   *
   * @param kind {@code ref}, {@code json} or {@code directory}
   * @param source the Git reference, file or directory as given
   * @param version the OPAA version: the reference, the version in the JSON form or {@code
   *     Arbeitsstand}
   * @param commit the commit, {@code unbekannt} when there is none
   * @param hasManifest {@code false} when the version has no manifest yet
   */
  public record Origin(
      String kind, String source, String version, String commit, boolean hasManifest) {}

  /** The area files in {@code directory}, a manifest directory such as the working tree's. */
  public static ManifestSnapshot fromDirectory(Path directory, String commit) {
    if (!Files.isDirectory(directory)) {
      throw new IllegalArgumentException("not a manifest directory: " + directory);
    }
    SortedMap<String, String> files = new TreeMap<>();
    try (Stream<Path> paths = Files.list(directory)) {
      for (Path file : paths.filter(p -> p.toString().endsWith(".yaml")).toList()) {
        files.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + directory, e);
    }
    Origin origin =
        new Origin("directory", displayPath(directory), "Arbeitsstand", commit, !files.isEmpty());
    return fromYaml(origin, files);
  }

  /**
   * The area files of {@code ref} in the Git repository containing {@code repository}. A reference
   * whose tree has no manifest yet yields an empty snapshot with {@link Origin#hasManifest()}
   * {@code false}; an unknown reference fails.
   */
  public static ManifestSnapshot fromGit(Path repository, String ref) {
    String commit = Git.resolve(repository, ref);
    SortedMap<String, String> files = new TreeMap<>();
    String listing =
        Git.run(
            repository,
            "ls-tree",
            "--full-tree",
            "--name-only",
            commit,
            "--",
            REPOSITORY_PATH + "/");
    for (String path : listing.lines().filter(line -> line.endsWith(".yaml")).toList()) {
      files.put(
          path.substring(path.lastIndexOf('/') + 1),
          Git.run(repository, "show", commit + ":" + path));
    }
    return fromYaml(new Origin("ref", ref, ref, commit, !files.isEmpty()), files);
  }

  /** The manifest held by the JSON form of the fact sheets in {@code file}. */
  public static ManifestSnapshot fromJson(Path file) {
    JsonNode root;
    try {
      root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
    return fromJsonNode(root, displayPath(file));
  }

  /**
   * The manifest held by {@code root}, the JSON form of the fact sheets or a configuration export,
   * both of which carry it as {@code manifest}; {@code source} names where it was read.
   */
  static ManifestSnapshot fromJsonNode(JsonNode root, String source) {
    JsonNode manifest = root.path("manifest");
    if (!manifest.isObject()) {
      throw new IllegalArgumentException(source + " holds no 'manifest' object");
    }
    Origin origin =
        new Origin(
            "json",
            source,
            text(root.path("opaaVersion"), UNKNOWN),
            commit(text(root.path("commit"), UNKNOWN)),
            true);
    SortedMap<String, String> areas = new TreeMap<>();
    manifest
        .path("areas")
        .properties()
        .forEach(e -> areas.put(e.getKey(), e.getValue().asString()));
    SortedMap<String, SortedMap<String, SortedMap<String, Object>>> sections = new TreeMap<>();
    for (Map.Entry<String, JsonNode> section : manifest.properties()) {
      if (!section.getValue().isArray()) {
        continue;
      }
      SortedMap<String, SortedMap<String, Object>> entries = new TreeMap<>();
      for (JsonNode node : section.getValue()) {
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = JSON.convertValue(node, Map.class);
        add(entries, section.getKey(), entry, source);
      }
      sections.put(section.getKey(), entries);
    }
    return new ManifestSnapshot(origin, areas, sections);
  }

  /** The area files {@code files} (file name to content) of the version {@code origin}. */
  static ManifestSnapshot fromYaml(Origin origin, SortedMap<String, String> files) {
    SortedMap<String, String> areas = new TreeMap<>();
    SortedMap<String, SortedMap<String, SortedMap<String, Object>>> sections = new TreeMap<>();
    files.forEach(
        (name, content) -> {
          String area = name.substring(0, name.length() - ".yaml".length());
          Map<String, Object> file = parse(content, origin.source() + ": " + name);
          if (file.get("title") != null) {
            areas.put(area, String.valueOf(file.get("title")));
          }
          Map<String, Object> defaults = mapping(file.get("defaults"));
          file.forEach(
              (section, value) -> {
                if (FILE_KEYS.contains(section) || value == null) {
                  return;
                }
                if (!(value instanceof List<?> list)) {
                  throw new IllegalArgumentException(
                      origin.source() + ": " + name + ": section '" + section + "' is no list");
                }
                SortedMap<String, SortedMap<String, Object>> entries =
                    sections.computeIfAbsent(section, s -> new TreeMap<>());
                for (Object item : list) {
                  Map<String, Object> entry = new LinkedHashMap<>(mapping(defaults.get(section)));
                  entry.putAll(mapping(item));
                  entry.put("area", area);
                  add(entries, section, entry, origin.source() + ": " + name);
                }
              });
          mapping(file.get("nonPersonalTables"))
              .forEach(
                  (table, columns) -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("area", area);
                    entry.put("table", table);
                    entry.put("columns", columns);
                    add(
                        sections.computeIfAbsent("nonPersonalTables", s -> new TreeMap<>()),
                        "nonPersonalTables",
                        entry,
                        origin.source() + ": " + name);
                  });
        });
    return new ManifestSnapshot(origin, areas, sections);
  }

  private static void add(
      SortedMap<String, SortedMap<String, Object>> entries,
      String section,
      Map<String, Object> entry,
      String where) {
    String id =
        IDENTITY_FIELDS.stream()
            .filter(field -> entry.get(field) != null)
            .map(field -> String.valueOf(entry.get(field)))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        where + ": an entry of " + section + " has no id"));
    SortedMap<String, Object> fields = new TreeMap<>();
    entry.forEach(
        (field, value) -> {
          if (!IDENTITY_FIELDS.contains(field) || !String.valueOf(value).equals(id)) {
            flatten(fields, field, value);
          }
        });
    if (entries.put(id, fields) != null) {
      throw new IllegalArgumentException(where + ": " + section + " '" + id + "' appears twice");
    }
  }

  private static void flatten(SortedMap<String, Object> fields, String path, Object value) {
    switch (value) {
      case null -> {}
      case Map<?, ?> map -> map.forEach((k, v) -> flatten(fields, path + "." + k, v));
      case List<?> list when list.stream().anyMatch(Map.class::isInstance) -> {
        for (int i = 0; i < list.size(); i++) {
          Map<String, Object> item = mapping(list.get(i));
          Object name = item.remove("name");
          String prefix = path + "[" + (name == null ? String.valueOf(i) : name) + "]";
          item.forEach((k, v) -> flatten(fields, prefix + "." + k, v));
        }
      }
      case List<?> list ->
          fields.put(path, list.stream().filter(v -> v != null).map(String::valueOf).toList());
      default -> fields.put(path, String.valueOf(value));
    }
  }

  private static Map<String, Object> parse(String content, String where) {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    try {
      Object root = new Yaml(new SafeConstructor(options)).load(content);
      if (root instanceof Map<?, ?>) {
        return mapping(root);
      }
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(where + ": unreadable YAML (" + e.getMessage() + ")", e);
    }
    throw new IllegalArgumentException(where + ": the file must hold a mapping");
  }

  private static Map<String, Object> mapping(Object value) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (value instanceof Map<?, ?> map) {
      map.forEach((k, v) -> result.put(String.valueOf(k), v));
    }
    return result;
  }

  /**
   * A commit id as the JSON form states it; anything else, which could pass as option, is unknown.
   */
  private static String commit(String value) {
    return COMMIT.matcher(value).matches() ? value : UNKNOWN;
  }

  /**
   * {@code path} relative to the working directory when it lies below it, else its file name, so
   * the output names no machine-specific absolute path.
   */
  static String displayPath(Path path) {
    Path absolute = path.toAbsolutePath().normalize();
    Path base = Path.of("").toAbsolutePath().normalize();
    if (absolute.startsWith(base) && !absolute.equals(base)) {
      return base.relativize(absolute).toString().replace('\\', '/');
    }
    Path name = absolute.getFileName();
    return name == null ? absolute.toString() : name.toString();
  }

  private static String text(JsonNode node, String fallback) {
    return node.isString() && !node.asString().isBlank() ? node.asString() : fallback;
  }

  /**
   * The {@code git} commands the delta needs, run in the repository containing a directory. Every
   * value from outside (reference, commit) follows {@code --end-of-options}, so none is read as an
   * option.
   */
  static final class Git {

    /** How long one {@code git} command may take. */
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private Git() {}

    /** The commit {@code ref} points to; fails for an unknown reference. */
    static String resolve(Path repository, String ref) {
      try {
        return run(
                repository,
                "rev-parse",
                "--verify",
                "--quiet",
                "--end-of-options",
                ref + "^{commit}")
            .strip();
      } catch (IllegalStateException e) {
        throw new IllegalArgumentException(
            "unknown Git reference '" + ref + "' in " + repository + " (fetched with tags?)", e);
      }
    }

    /** The commit {@code HEAD} points to, or {@code unbekannt} outside a repository. */
    static String head(Path repository) {
      try {
        return resolve(repository, "HEAD");
      } catch (IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
        return UNKNOWN;
      }
    }

    /** The subjects of the commits reachable from {@code to} but not from {@code from}. */
    static List<String> subjects(Path repository, String from, String to) {
      return run(repository, "log", "--format=%s", "--end-of-options", from + ".." + to)
          .lines()
          .toList();
    }

    /**
     * Runs {@code git -C repository arguments}; output and errors go to temporary files, so neither
     * stream can fill up and block the process, and a command past {@link #TIMEOUT} is killed.
     */
    static String run(Path repository, String... arguments) {
      List<String> command = new ArrayList<>(List.of("git", "-C", repository.toString()));
      command.addAll(List.of(arguments));
      Path out = null;
      Path err = null;
      try {
        out = Files.createTempFile("opaa-git", ".out");
        err = Files.createTempFile("opaa-git", ".err");
        Process process =
            new ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                .redirectOutput(out.toFile())
                .redirectError(err.toFile())
                .start();
        if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          throw new IllegalStateException(
              String.join(" ", command) + " timed out after " + TIMEOUT);
        }
        if (process.exitValue() != 0) {
          throw new IllegalStateException(
              String.join(" ", command)
                  + " failed ("
                  + process.exitValue()
                  + "): "
                  + Files.readString(err, StandardCharsets.UTF_8).strip());
        }
        return Files.readString(out, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException("cannot run " + String.join(" ", command), e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted: " + String.join(" ", command), e);
      } finally {
        deleteQuietly(out);
        deleteQuietly(err);
      }
    }

    private static java.io.File nullDevice() {
      return new java.io.File(
          System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows")
              ? "NUL"
              : "/dev/null");
    }

    private static void deleteQuietly(Path file) {
      if (file == null) {
        return;
      }
      try {
        Files.deleteIfExists(file);
      } catch (IOException e) {
        file.toFile().deleteOnExit();
      }
    }
  }
}
