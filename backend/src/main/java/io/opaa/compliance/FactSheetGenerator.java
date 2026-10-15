package io.opaa.compliance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Entry point of {@code ./gradlew complianceDocs}: reads the manifest delivered with the build and
 * writes every {@link FactSheet} as {@code <name>.md} plus the JSON form {@value #JSON_FILE} into
 * the output directory, replacing the files of an earlier run.
 *
 * <p>Arguments: output directory, OPAA version, commit. The generation date is today in UTC.
 */
public final class FactSheetGenerator {

  /** File name of the JSON form next to the sheets. */
  public static final String JSON_FILE = "faktenblaetter.json";

  private FactSheetGenerator() {}

  public static void main(String[] args) {
    if (args.length != 3) {
      throw new IllegalArgumentException(
          "usage: FactSheetGenerator <output directory> <version> <commit>");
    }
    FactSheetProvenance provenance =
        new FactSheetProvenance(args[1], args[2], LocalDate.now(ZoneOffset.UTC));
    List<Path> written = write(Path.of(args[0]), ComplianceManifestLoader.load(), provenance);
    written.forEach(file -> System.out.println("written: " + file));
  }

  /** Writes the sheets and the JSON form of {@code manifest} into {@code directory}. */
  static List<Path> write(
      Path directory, ComplianceManifest manifest, FactSheetProvenance provenance) {
    try {
      clear(directory);
      Files.createDirectories(directory);
      Map<FactSheet, String> sheets = FactSheetRenderer.render(manifest, provenance);
      List<Path> written = new ArrayList<>();
      for (Map.Entry<FactSheet, String> sheet : sheets.entrySet()) {
        written.add(
            Files.writeString(
                directory.resolve(sheet.getKey().fileName()),
                sheet.getValue(),
                StandardCharsets.UTF_8));
      }
      written.add(
          Files.writeString(
              directory.resolve(JSON_FILE),
              FactSheetRenderer.renderJson(manifest, provenance),
              StandardCharsets.UTF_8));
      return written;
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write the fact sheets to " + directory, e);
    }
  }

  private static void clear(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) {
      return;
    }
    try (Stream<Path> files = Files.list(directory)) {
      for (Path file : files.toList()) {
        Files.delete(file);
      }
    }
  }
}
