package io.opaa.indexing.pipeline.markdown;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Markdown pipeline (#1061; ingestion-pipelines.md Teil 2): the cut follows ATX headings level
 * 1-3, every chunk carries its heading path (in text and metadata alike), and a heading with no
 * body still becomes its own chunk instead of disappearing.
 */
class MarkdownDocumentPipelineTest {

  @TempDir Path tempDir;

  private final MarkdownDocumentPipeline pipeline = new MarkdownDocumentPipeline();

  @Test
  void claimsExactlyMarkdown() {
    assertThat(pipeline.handledFormats()).containsExactly(".md");
    assertThat(pipeline.id()).isEqualTo("markdown");
    assertThat(pipeline.version()).isEqualTo((short) 1);
  }

  private static final String SATZUNG =
      """
      # Verwaltungsgebuehrensatzung

      Diese Satzung regelt die Gebuehren der Stadt.

      ## Personaldokumente

      Fuer die Ausstellung eines Personalausweises werden Gebuehren erhoben.

      ### Ermaessigung

      Es gilt eine Ermaessigung fuer Minderjaehrige.

      ## Gewerbeanmeldung

      Die Gewerbeanmeldung kostet 26,00 EUR.
      """;

  @Test
  void cutsFollowAtxHeadingsWithOneChunkPerSectionAndTheHeadingInTheChunkText() throws IOException {
    DocumentPipelineResult result = pipeline.run(sourceFor(SATZUNG));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    // Verwaltungsgebuehrensatzung (h1), Personaldokumente (h2), Ermaessigung (h3),
    // Gewerbeanmeldung (h2).
    assertThat(result.chunks()).hasSize(4);
    assertThat(result.chunks().get(0).getText())
        .startsWith("Verwaltungsgebuehrensatzung")
        .contains("regelt die Gebuehren");
    assertThat(result.chunks().get(0).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Abschn. Verwaltungsgebuehrensatzung");
    assertThat(result.chunks().get(1).getText())
        .startsWith("Verwaltungsgebuehrensatzung › Personaldokumente")
        .contains("Personalausweises");
    assertThat(result.chunks().get(2).getText())
        .startsWith("Verwaltungsgebuehrensatzung › Personaldokumente › Ermaessigung")
        .contains("Minderjaehrige");
    assertThat(result.chunks().get(3).getText())
        .startsWith("Verwaltungsgebuehrensatzung › Gewerbeanmeldung")
        .contains("26,00 EUR");
    assertThat(result.chunks().get(3).getMetadata().get(ChunkingService.LOCATION_METADATA_KEY))
        .isEqualTo("Abschn. Verwaltungsgebuehrensatzung › Gewerbeanmeldung");
  }

  @Test
  void aHeadingWithNoBodyTextStillBecomesItsOwnChunkInsteadOfNoExtractableText()
      throws IOException {
    DocumentPipelineResult result = pipeline.run(sourceFor("# Nur eine Ueberschrift"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText()).isEqualTo("Nur eine Ueberschrift");
  }

  @Test
  void noRedundantTitleOnlyChunkForAnOrdinaryTitledDocument() throws IOException {
    String text =
        """
        # Titel

        ## Abschnitt

        Text.
        """;

    DocumentPipelineResult result = pipeline.run(sourceFor(text));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .startsWith("Titel › Abschnitt")
        .contains("Text.");
  }

  @Test
  void headingsDeeperThanLevelThreeDoNotCutButStayInTheSurroundingSection() throws IOException {
    String text =
        """
        # Titel

        #### Tief verschachtelt

        Text unter der tiefen Ueberschrift.
        """;

    DocumentPipelineResult result = pipeline.run(sourceFor(text));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .contains("Tief verschachtelt")
        .contains("Text unter der tiefen Ueberschrift");
  }

  @Test
  void aHashInsideAFencedCodeBlockIsNotTreatedAsAHeading() throws IOException {
    String text =
        """
        # Titel

        ```
        # dies ist kein Heading
        ```

        Text danach.
        """;

    DocumentPipelineResult result = pipeline.run(sourceFor(text));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().getText())
        .contains("dies ist kein Heading")
        .contains("Text danach");
  }

  @Test
  void aBlankDocumentHasNoContent() throws IOException {
    DocumentPipelineResult result = pipeline.run(sourceFor("   \n\n  "));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void aSectionExceedingTheSoftBudgetIsSplitFurther() throws IOException {
    StringBuilder text = new StringBuilder("# Titel\n\n");
    for (int i = 0; i < 200; i++) {
      text.append("Absatz Nummer ").append(i).append(" mit etwas Fuelltext fuer die Groesse.\n\n");
    }

    DocumentPipelineResult result = pipeline.run(sourceFor(text.toString()));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSizeGreaterThanOrEqualTo(2);
    assertThat(result.chunks())
        .allSatisfy(
            chunk ->
                assertThat(chunk.getText().length())
                    .isLessThanOrEqualTo(HeadingSectionSplitter.HARD_CHUNK_CHAR_LIMIT));
  }

  private DocumentPipelineSource sourceFor(String markdown) throws IOException {
    Path file = tempDir.resolve("dokument.md");
    Files.writeString(file, markdown, StandardCharsets.UTF_8);
    return DocumentPipelineSource.ofFile(file, "dokument.md", ".md");
  }
}
