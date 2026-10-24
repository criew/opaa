package io.opaa.compliance;

import io.opaa.compliance.InstanceMatch.Export;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entry point of {@code ./gradlew complianceInstanceMatch}: writes {@value #MARKDOWN_FILE} and
 * {@value #JSON_FILE} into an output directory. Reads files only, so it runs offline and without
 * access to the instance, also at the data protection officer's.
 *
 * <p>Arguments: {@code --out <dir>}, {@code --delta <file>} (the {@code manifest-delta.json} of
 * {@code ./gradlew complianceDelta} or of the update package), {@code --export <file>} (the JSON of
 * {@code GET /api/v1/admin/compliance/data-collection-disclosure}) and optionally {@code
 * --previous-export <file>}, an earlier export of the same instance.
 */
public final class InstanceMatchGenerator {

  /** File name of the Markdown form. */
  public static final String MARKDOWN_FILE = "instanz-abgleich.md";

  /** File name of the JSON form. */
  public static final String JSON_FILE = "instanz-abgleich.json";

  private static final Set<String> OPTIONS =
      Set.of("--out", "--delta", "--export", "--previous-export");
  private static final String USAGE =
      "usage: InstanceMatchGenerator --out <dir> --delta <manifest-delta.json> --export <file>"
          + " [--previous-export <file>]";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private InstanceMatchGenerator() {}

  public static void main(String[] args) {
    Map<String, String> options = options(args);
    Path delta = Path.of(require(options, "--delta"));
    Path export = Path.of(require(options, "--export"));
    Path previous =
        options.containsKey("--previous-export") ? Path.of(options.get("--previous-export")) : null;
    JsonNode exportNode = read(export);
    JsonNode previousNode = previous == null ? null : read(previous);
    InstanceMatch match =
        InstanceMatch.of(
            read(delta),
            info(export, exportNode),
            exportNode,
            previous == null ? null : info(previous, previousNode),
            previousNode);
    write(Path.of(require(options, "--out")), match)
        .forEach(file -> System.out.println("written: " + file));
  }

  /** Writes both forms of {@code match} into {@code directory}, replacing earlier ones. */
  static List<Path> write(Path directory, InstanceMatch match) {
    try {
      Files.createDirectories(directory);
      return List.of(
          Files.writeString(
              directory.resolve(MARKDOWN_FILE),
              InstanceMatchRenderer.markdown(match),
              StandardCharsets.UTF_8),
          Files.writeString(
              directory.resolve(JSON_FILE),
              InstanceMatchRenderer.json(match),
              StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write the instance match to " + directory, e);
    }
  }

  /** What the export says about itself; the file is named without its directory. */
  static Export info(Path file, JsonNode export) {
    return new Export(
        ManifestSnapshot.displayPath(file),
        orUnknown(InstanceMatch.text(export.path("opaaVersion"))),
        orUnknown(InstanceMatch.text(export.path("commit"))),
        orUnknown(InstanceMatch.text(export.path("generatedAt"))));
  }

  private static String orUnknown(String value) {
    return value.isEmpty() ? ManifestSnapshot.UNKNOWN : value;
  }

  private static JsonNode read(Path file) {
    try {
      return JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    } catch (JacksonException e) {
      throw new IllegalArgumentException(file + " is no JSON: " + e.getOriginalMessage(), e);
    }
  }

  private static Map<String, String> options(String[] args) {
    Map<String, String> options = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i += 2) {
      if (!OPTIONS.contains(args[i]) || i + 1 >= args.length || args[i + 1].isBlank()) {
        throw new IllegalArgumentException("unexpected argument '" + args[i] + "'; " + USAGE);
      }
      if (options.put(args[i], args[i + 1]) != null) {
        throw new IllegalArgumentException(args[i] + " given twice; " + USAGE);
      }
    }
    return options;
  }

  private static String require(Map<String, String> options, String option) {
    String value = options.get(option);
    if (value == null) {
      throw new IllegalArgumentException(option + " is missing; " + USAGE);
    }
    return value;
  }
}
