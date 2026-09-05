package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.metadata.CoreMetadataExtractor;
import io.opaa.indexing.metadata.ExtractedCoreMetadata;
import io.opaa.indexing.metadata.TestVocabularies;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.indexing.pipeline.markdown.MarkdownDocumentPipeline;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The extraction fill level of the {@code verwaltung} corpus (issue #1070, metadata-schema.md
 * "Messung und Abnahme", point 3), Docker-free: every corpus document's properties are read by the
 * production {@link MarkdownDocumentPipeline} and handed to the production {@link
 * CoreMetadataExtractor} with the delivered vocabulary - the same two steps the indexing run
 * performs, minus the database. The numbers are pinned so a generator or extractor change that
 * moves them is a deliberate decision, recorded in {@code eval/corpus/verwaltung/MAINTENANCE.md}.
 *
 * <p>The corpus carries exactly one document without a Datum/Stand and exactly one without a
 * Dokumentart by construction (the two Leerwert documents); the other Dokumentart gaps are the
 * {@code formularhinweis}, {@code vertretungsregelung} and {@code geschaeftsverteilungsplan}
 * values, which the delivered vocabulary does not know - a fact about this corpus the fill level
 * makes visible instead of a defect of the extractor.
 */
class VerwaltungCorpusMetadataFillLevelTest {

  private static final int EXPECTED_DOCUMENT_COUNT = 72;
  private static final int EXPECTED_WITH_TITLE = 72;
  private static final int EXPECTED_WITH_DOCUMENT_TYPE = 49;
  private static final int EXPECTED_WITH_DATE = 71;

  static final String WITHOUT_DATE = "verwaltung-dienstanweisung-aktenaufbewahrung.md";
  static final String WITHOUT_DOCUMENT_TYPE = "verwaltung-leitfaden-barrierefreiheit.md";

  @Test
  void reportsAndPinsTheFillLevelPerCoreField() throws IOException {
    Path corpusDir = RepoPaths.evalDir().resolve("corpus").resolve("verwaltung");
    CorpusManifest.VerificationResult manifest =
        CorpusManifest.verify(corpusDir, corpusDir.resolve("MANIFEST.sha256"));
    assertThat(manifest.isValid()).isTrue();
    assertThat(manifest.fileNames()).hasSize(EXPECTED_DOCUMENT_COUNT);

    MarkdownDocumentPipeline pipeline = new MarkdownDocumentPipeline();
    var vocabulary = TestVocabularies.delivered();
    int withTitle = 0;
    int withType = 0;
    int withDate = 0;
    List<String> withoutType = new ArrayList<>();
    List<String> withoutDate = new ArrayList<>();
    for (String fileName : manifest.fileNames()) {
      DocumentProperties properties =
          pipeline.readProperties(
              DocumentPipelineSource.ofFile(corpusDir.resolve(fileName), fileName, ".md"));
      ExtractedCoreMetadata extracted =
          CoreMetadataExtractor.extract(fileName, properties, vocabulary);
      if (extracted.title().isPresent()) {
        withTitle++;
      }
      if (extracted.documentTypeCode().isPresent()) {
        withType++;
      } else {
        withoutType.add(fileName);
      }
      if (extracted.date().isPresent()) {
        withDate++;
      } else {
        withoutDate.add(fileName);
      }
    }
    int n = manifest.fileNames().size();
    System.out.println(
        String.format(
            Locale.ROOT,
            "verwaltung metadata fill level: title %d/%d (%.1f%%), documentType %d/%d (%.1f%%), "
                + "documentDate %d/%d (%.1f%%); without documentType: %s; without documentDate: %s",
            withTitle,
            n,
            100.0 * withTitle / n,
            withType,
            n,
            100.0 * withType / n,
            withDate,
            n,
            100.0 * withDate / n,
            withoutType,
            withoutDate));

    assertThat(withTitle).isEqualTo(EXPECTED_WITH_TITLE);
    assertThat(withType).isEqualTo(EXPECTED_WITH_DOCUMENT_TYPE);
    assertThat(withDate).isEqualTo(EXPECTED_WITH_DATE);
    // The two Leerwert documents of #1070 are the ones the Leerwert-Regel cases point at.
    assertThat(withoutDate).containsExactly(WITHOUT_DATE);
    assertThat(withoutType).contains(WITHOUT_DOCUMENT_TYPE);
  }
}
