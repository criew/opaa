package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The checked-in placeholder target list {@code .github/fact-sheet-anchors.txt} equals what the
 * shipped manifest renders. {@code check_behoerden_headers.sh} checks the placeholders and the name
 * list under {@code docs/behoerden/} against it, so a template change needs no backend run, and a
 * manifest change that moves an anchor fails here until the list is regenerated.
 */
class FactSheetReferenceTest {

  private static final Path ANCHOR_FILE = Path.of("../.github/fact-sheet-anchors.txt");

  @Test
  void theCheckedInPlaceholderTargetsAreCurrent() throws IOException {
    String expected = FactSheetGenerator.anchorFile(ComplianceManifestLoader.load());

    assertThat(Files.readString(ANCHOR_FILE, StandardCharsets.UTF_8))
        .as("%s is stale: run ./gradlew complianceAnchors in backend/ and commit it", ANCHOR_FILE)
        .isEqualTo(expected);
  }

  @Test
  void everySheetNameAndEveryAnchorIsATarget() {
    List<String> targets = FactSheetRenderer.placeholderTargets(ComplianceManifestLoader.load());

    for (FactSheet sheet : FactSheet.values()) {
      assertThat(targets).contains(sheet.sheetName());
    }
    assertThat(targets)
        .doesNotHaveDuplicates()
        .allMatch(target -> target.matches("[a-z]+(#[a-z0-9]+(-[a-z0-9]+)*)?"))
        .contains("protokollereignisse#asset-grant-granted", "rollensicht#auditor");
  }
}
