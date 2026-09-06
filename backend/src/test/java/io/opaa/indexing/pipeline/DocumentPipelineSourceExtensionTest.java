package io.opaa.indexing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The dispatch key a multi-format pipeline (Tabular's XLSX/CSV/ODS, Mail's EML/MSG) reads: detected
 * content first, the file name only where detection resolved nothing.
 */
class DocumentPipelineSourceExtensionTest {

  @Test
  void theDetectedExtensionWinsOverAMisleadingFileName() {
    // regression guard: a genuine XLSX misnamed .csv is routed on its content and must be parsed
    // as what it is, not as what it claims to be.
    DocumentPipelineSource source =
        DocumentPipelineSource.ofFile(Path.of("tabelle.csv"), "tabelle.csv", ".xlsx");

    assertThat(source.effectiveExtension()).isEqualTo(".xlsx");
  }

  @Test
  void withoutADetectedExtensionTheFileNameSuffixIsUsedLowerCased() {
    DocumentPipelineSource source =
        DocumentPipelineSource.ofFile(Path.of("Nachricht.MSG"), "Nachricht.MSG");

    assertThat(source.effectiveExtension()).isEqualTo(".msg");
  }

  @Test
  void aNameWithoutASuffixResolvesToNoExtension() {
    assertThat(DocumentPipelineSource.ofFile(Path.of("tabelle"), "tabelle").effectiveExtension())
        .isNull();
    assertThat(DocumentPipelineSource.ofExtractedText("text", "Schlagzeile").effectiveExtension())
        .isNull();
  }
}
