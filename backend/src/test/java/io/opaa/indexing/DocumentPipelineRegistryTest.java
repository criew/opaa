package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The routing contract of {@link DocumentPipelineRegistry} (#1056, ingestion-pipelines.md Teil 1):
 * the pipeline follows the <em>detected content</em>, the Markdown/Klartext special rule (content
 * and extension) still applies, and everything without its own pipeline keeps going through the
 * Tika fallback - which is what makes the abstraction verhaltensneutral for the existing bestand.
 */
class DocumentPipelineRegistryTest {

  private static final String PDF = "application/pdf";
  private static final String PLAIN_TEXT = "text/plain";
  private static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  private final TikaFallbackPipeline fallback =
      new TikaFallbackPipeline(new DocumentService(), new ChunkingService(properties()));

  private static IndexingProperties properties() {
    return new IndexingProperties(1000, 100, 50, null, null, List.of(), null, null, null, 1);
  }

  /** A stand-in for a future format pipeline - the whole point of the open-closed criterion. */
  private record FakePipeline(String id, short version, Set<String> handledFormats)
      implements DocumentPipeline {

    @Override
    public DocumentPipelineResult run(DocumentPipelineSource source) {
      return DocumentPipelineResult.chunked(List.of());
    }
  }

  private DocumentPipelineRegistry registryWith(DocumentPipeline... specialized) {
    return new DocumentPipelineRegistry(
        java.util.stream.Stream.concat(
                java.util.stream.Stream.of((DocumentPipeline) fallback),
                java.util.Arrays.stream(specialized))
            .toList(),
        fallback);
  }

  @Test
  void everyFormatUsesTheFallbackWhileNoSpecializedPipelineIsRegistered() {
    DocumentPipelineRegistry registry = registryWith();

    assertThat(registry.pipelineFor("satzung.pdf", PDF)).isSameAs(fallback);
    assertThat(registry.pipelineFor("vermerk.docx", DOCX)).isSameAs(fallback);
    assertThat(registry.pipelineFor("notiz.md", PLAIN_TEXT)).isSameAs(fallback);
    assertThat(registry.fallbackPipeline()).isSameAs(fallback);
  }

  @Test
  void routesOnTheDetectedContentNotOnTheFileExtension() {
    DocumentPipeline pdfPipeline = new FakePipeline("pdf", (short) 1, Set.of(".pdf"));
    DocumentPipelineRegistry registry = registryWith(pdfPipeline);

    // The core #404 rule carried into routing: a PDF misnamed .docx in a gewachsene Ablage still
    // reaches the PDF pipeline, and a DOCX misnamed .pdf never does.
    assertThat(registry.pipelineFor("eigentlich-ein.docx", PDF)).isSameAs(pdfPipeline);
    assertThat(registry.pipelineFor("eigentlich-ein.pdf", DOCX)).isSameAs(fallback);
  }

  @Test
  void markdownAndPlainTextStillNeedTheirOwnExtension() {
    DocumentPipeline markdownPipeline = new FakePipeline("markdown", (short) 1, Set.of(".md"));
    DocumentPipeline textPipeline = new FakePipeline("text", (short) 1, Set.of(".txt"));
    DocumentPipelineRegistry registry = registryWith(markdownPipeline, textPipeline);

    // Content alone cannot tell the two apart, so the extension decides which of them it is -
    // exactly the admission rule, reused rather than re-implemented.
    assertThat(registry.pipelineFor("handbuch.md", PLAIN_TEXT)).isSameAs(markdownPipeline);
    assertThat(registry.pipelineFor("handbuch.txt", PLAIN_TEXT)).isSameAs(textPipeline);
    // Plain-text content under an extension that claims neither is not admitted at all, so it can
    // only ever fall back - the registry never widens what SupportedDocumentFormats accepts.
    assertThat(registry.pipelineFor("export.csv", PLAIN_TEXT)).isSameAs(fallback);
  }

  @Test
  void aFormatWithoutItsOwnPipelineKeepsUsingTheFallback() {
    DocumentPipeline pdfPipeline = new FakePipeline("pdf", (short) 1, Set.of(".pdf"));
    DocumentPipelineRegistry registry = registryWith(pdfPipeline);

    assertThat(registry.pipelineFor("vermerk.docx", DOCX)).isSameAs(fallback);
  }

  @Test
  void twoPipelinesClaimingTheSameFormatFailFast() {
    DocumentPipeline first = new FakePipeline("pdf-a", (short) 1, Set.of(".pdf"));
    DocumentPipeline second = new FakePipeline("pdf-b", (short) 1, Set.of(".pdf"));

    assertThatThrownBy(() -> registryWith(first, second))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(".pdf");
  }

  @Test
  void reportsEveryRegisteredPipelineWithItsCurrentVersion() {
    DocumentPipeline pdfPipeline = new FakePipeline("pdf", (short) 3, Set.of(".pdf"));

    assertThat(registryWith(pdfPipeline).pipelines())
        .extracting(DocumentPipeline::id, DocumentPipeline::version)
        .containsExactlyInAnyOrder(
            org.assertj.core.api.Assertions.tuple(TikaFallbackPipeline.ID, (short) 1),
            org.assertj.core.api.Assertions.tuple("pdf", (short) 3));
  }
}
