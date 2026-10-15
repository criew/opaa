package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The fact sheet names and anchors the documents under {@code docs/behoerden/} rely on: every name
 * the README lists and the header script accepts renders a sheet, no sheet is missing from either
 * list, and every placeholder in a template resolves to a rendered sheet and, with an anchor, to an
 * anchor in it.
 */
class FactSheetReferenceTest {

  private static final Path FOLDER = Path.of("../docs/behoerden");
  private static final Path README = FOLDER.resolve("README.md");
  private static final Path HEADER_SCRIPT =
      Path.of("../.github/scripts/check_behoerden_headers.sh");

  /** The README bullet "`<name>` ist eines der Faktenblätter: `a`, `b`, ..." up to its period. */
  private static final Pattern README_NAMES =
      Pattern.compile("`<name>` ist eines der Faktenblätter:([^.]*)\\.");

  private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
  private static final Pattern SCRIPT_NAMES = Pattern.compile("(?m)^FACT_SHEETS='([^']*)'$");
  private static final Pattern PLACEHOLDER =
      Pattern.compile("\\{\\{faktenblatt:([a-z0-9-]+)(?:#([a-z0-9-]+))?}}");

  private static final Map<FactSheet, String> SHEETS =
      FactSheetRenderer.render(
          ComplianceManifestLoader.load(),
          new FactSheetProvenance("0.0.0-dev", "test", LocalDate.of(2026, 10, 15)));

  @Test
  void everyPlaceholderNameTheReadmeListsRendersASheet() throws IOException {
    Matcher matcher = README_NAMES.matcher(read(README).replaceAll("\\s+", " "));
    assertThat(matcher.find()).as("name list in %s", README).isTrue();
    List<String> names = new ArrayList<>();
    Matcher name = BACKTICKED.matcher(matcher.group(1));
    while (name.find()) {
      names.add(name.group(1));
    }

    assertRendersExactlyTheSheets(names, README);
  }

  @Test
  void theHeaderScriptAcceptsExactlyTheRenderedSheets() throws IOException {
    Matcher matcher = SCRIPT_NAMES.matcher(read(HEADER_SCRIPT));
    assertThat(matcher.find()).as("FACT_SHEETS in %s", HEADER_SCRIPT).isTrue();

    assertRendersExactlyTheSheets(Arrays.asList(matcher.group(1).split("\\|")), HEADER_SCRIPT);
  }

  @Test
  void everyPlaceholderUnderDocsBehoerdenResolvesToARenderedSheetAndAnchor() throws IOException {
    List<Path> documents;
    try (Stream<Path> files = Files.walk(FOLDER)) {
      documents = files.filter(file -> file.toString().endsWith(".md")).sorted().toList();
    }
    assertThat(documents).contains(README);

    int placeholders = 0;
    for (Path document : documents) {
      Matcher matcher = PLACEHOLDER.matcher(read(document));
      while (matcher.find()) {
        placeholders++;
        String placeholder = matcher.group();
        FactSheet sheet = FactSheet.bySheetName(matcher.group(1)).orElse(null);
        assertThat(sheet).as("%s in %s names no fact sheet", placeholder, document).isNotNull();
        if (matcher.group(2) != null) {
          assertThat(FactSheetRendererTest.anchorSet(SHEETS.get(sheet)))
              .as("%s in %s names no anchor of the rendered sheet", placeholder, document)
              .contains(matcher.group(2));
        }
      }
    }
    assertThat(placeholders).isPositive();
  }

  private static void assertRendersExactlyTheSheets(List<String> names, Path source) {
    for (String name : names) {
      assertThat(FactSheet.bySheetName(name))
          .as("'%s' in %s renders no fact sheet", name, source)
          .isPresent();
      assertThat(SHEETS.get(FactSheet.bySheetName(name).orElseThrow())).isNotBlank();
    }
    assertThat(names)
        .as("fact sheet names in %s", source)
        .containsExactlyInAnyOrderElementsOf(
            Arrays.stream(FactSheet.values()).map(FactSheet::sheetName).toList());
  }

  private static String read(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }
}
