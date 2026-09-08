package io.opaa.indexing.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.chunk.ChunkingService;
import io.opaa.indexing.document.DocumentService;
import io.opaa.indexing.format.file.fallback.TikaFallbackFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The routing contract of {@link DocumentFormatRegistry} (ingestion-pipelines.md Teil 1): the
 * pipeline follows the <em>detected content</em>, the Markdown/Klartext special rule (content and
 * extension) still applies, and everything without its own pipeline keeps going through the Tika
 * fallback - which is what makes the abstraction verhaltensneutral for the existing bestand.
 */
class DocumentFormatRegistryTest {

  private static final String PDF = "application/pdf";
  private static final String PLAIN_TEXT = "text/plain";
  private static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  private static final FormatAdmission PDF_ADMISSION = FormatAdmission.detectedAs(".pdf", PDF);

  private final TikaFallbackFormat fallback =
      new TikaFallbackFormat(new DocumentService(), new ChunkingService(properties()));

  private static IndexingProperties properties() {
    return new IndexingProperties(1000, 100, 50, null, null, null, null, 1);
  }

  /** A stand-in for a future format pipeline - the whole point of the open-closed criterion. */
  private record FakePipeline(String id, short version, Set<FormatAdmission> admittedFormats)
      implements DocumentFormat {

    @Override
    public DocumentFormatResult run(DocumentFormatSource source) {
      return DocumentFormatResult.chunked(List.of());
    }
  }

  /** A stand-in pipeline declaring an arbitrary, non-default passthrough key set. */
  private record FakePipelineWithPassthroughKeys(
      String id,
      short version,
      Set<FormatAdmission> admittedFormats,
      Set<String> passthroughMetadataKeys)
      implements DocumentFormat {

    @Override
    public DocumentFormatResult run(DocumentFormatSource source) {
      return DocumentFormatResult.chunked(List.of());
    }
  }

  /**
   * A stand-in pipeline violating the "never null" contract of {@code passthroughMetadataKeys()}.
   */
  private record FakePipelineWithNullPassthroughKeys(
      String id, short version, Set<FormatAdmission> admittedFormats) implements DocumentFormat {

    @Override
    public DocumentFormatResult run(DocumentFormatSource source) {
      return DocumentFormatResult.chunked(List.of());
    }

    @Override
    public Set<String> passthroughMetadataKeys() {
      return null;
    }
  }

  @Test
  void allPassthroughMetadataKeysIsTheUnionOverEveryRegisteredPipeline() {
    DocumentFormat pdfPipeline =
        new FakePipelineWithPassthroughKeys(
            "pdf", (short) 1, Set.of(PDF_ADMISSION), Set.of("location"));
    DocumentFormat mailPipeline =
        new FakePipelineWithPassthroughKeys(
            "email",
            (short) 1,
            Set.of(FormatAdmission.textTolerant(".eml", "message/rfc822")),
            Set.of("location", "mail_subject"));
    DocumentFormatRegistry registry = registryWith(pdfPipeline, mailPipeline);

    // fallback's own default (location) is part of the union too - it is a registered pipeline
    // like any other.
    assertThat(registry.allPassthroughMetadataKeys())
        .containsExactlyInAnyOrder("location", "mail_subject");
  }

  @Test
  void aPipelineReturningNullFromPassthroughMetadataKeysFailsFastAtConstruction() {
    DocumentFormat brokenPipeline =
        new FakePipelineWithNullPassthroughKeys(
            "broken",
            (short) 1,
            Set.of(FormatAdmission.detectedAs(".broken", "application/x-broken")));

    assertThatThrownBy(() -> registryWith(brokenPipeline))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("broken");
  }

  /**
   * ADR-0024, Entscheidung 5: the core-field chunk keys hang on the document and are written by
   * storeChunks alone - a pipeline that declares one as passthrough is rejected at startup.
   */
  @Test
  void aPipelineDeclaringACoreMetadataKeyAsPassthroughFailsFastAtConstruction() {
    DocumentFormat overreaching =
        new FakePipelineWithPassthroughKeys(
            "overreaching",
            (short) 1,
            Set.of(FormatAdmission.detectedAs(".over", "application/x-over")),
            Set.of("location", io.opaa.indexing.metadata.CoreMetadataChunkKeys.DOCUMENT_TYPE));

    assertThatThrownBy(() -> registryWith(overreaching))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("overreaching")
        .hasMessageContaining("doc_type");
  }

  private DocumentFormatRegistry registryWith(DocumentFormat... specialized) {
    return new DocumentFormatRegistry(
        java.util.stream.Stream.concat(
                java.util.stream.Stream.of((DocumentFormat) fallback),
                java.util.Arrays.stream(specialized))
            .toList(),
        fallback);
  }

  @Test
  void everyFormatUsesTheFallbackWhileNoSpecializedPipelineIsRegistered() {
    DocumentFormatRegistry registry = registryWith();

    assertThat(registry.pipelineFor("satzung.pdf", PDF)).isSameAs(fallback);
    assertThat(registry.pipelineFor("vermerk.docx", DOCX)).isSameAs(fallback);
    assertThat(registry.pipelineFor("notiz.md", PLAIN_TEXT)).isSameAs(fallback);
    assertThat(registry.fallbackPipeline()).isSameAs(fallback);
  }

  @Test
  void routesOnTheDetectedContentNotOnTheFileExtension() {
    DocumentFormat pdfPipeline = new FakePipeline("pdf", (short) 1, Set.of(PDF_ADMISSION));
    DocumentFormatRegistry registry = registryWith(pdfPipeline);

    // Content-based admission carried into routing: a PDF misnamed .docx in a gewachsene Ablage
    // still
    // reaches the PDF pipeline, and a DOCX misnamed .pdf never does.
    assertThat(registry.pipelineFor("eigentlich-ein.docx", PDF)).isSameAs(pdfPipeline);
    assertThat(registry.pipelineFor("eigentlich-ein.pdf", DOCX)).isSameAs(fallback);
  }

  @Test
  void markdownAndPlainTextStillNeedTheirOwnExtension() {
    DocumentFormat markdownPipeline =
        new FakePipeline(
            "markdown", (short) 1, Set.of(FormatAdmission.textTolerant(".md", "text/markdown")));
    DocumentFormatRegistry registry = registryWith(markdownPipeline);

    // Content alone cannot tell the two apart, so the extension decides which of them it is -
    // exactly the admission rule, reused rather than re-implemented. ".txt" is admitted by the
    // fallback's own declaration, which is why it lands there rather than in the Markdown pipeline.
    assertThat(registry.pipelineFor("handbuch.md", PLAIN_TEXT)).isSameAs(markdownPipeline);
    assertThat(registry.pipelineFor("handbuch.txt", PLAIN_TEXT)).isSameAs(fallback);
    // ".txt" reaches the fallback because it is admitted and unclaimed, not because it is
    // unadmitted - the two are indistinguishable from the routed pipeline alone.
    assertThat(registry.supportedFormats().decideForFileName("handbuch.txt", PLAIN_TEXT))
        .isEqualTo(new SupportedDocumentFormats.ContentDecision(true, ".txt", false));
    // ".csv" is admitted by TabularDocumentFormat in the application, by nothing in this registry -
    // unadmitted content routes to the fallback for the other reason.
    assertThat(registry.pipelineFor("export.csv", PLAIN_TEXT)).isSameAs(fallback);
    assertThat(registry.supportedFormats().decideForFileName("export.csv", PLAIN_TEXT).supported())
        .isFalse();
  }

  @Test
  void anAdmittedFormatWithoutItsOwnPipelineKeepsUsingTheFallback() {
    DocumentFormat pdfPipeline = new FakePipeline("pdf", (short) 1, Set.of(PDF_ADMISSION));
    DocumentFormatRegistry registry = registryWith(pdfPipeline);

    // ".doc" is admitted - by the fallback's own declaration - and claimed by nobody, the case
    // this fallback exists for; the decision below is what tells it apart from ".docx", which is
    // not admitted here at all and reaches the same pipeline for the opposite reason.
    assertThat(registry.pipelineFor("altakte.doc", "application/msword")).isSameAs(fallback);
    assertThat(registry.supportedFormats().decideForFileName("altakte.doc", "application/msword"))
        .isEqualTo(new SupportedDocumentFormats.ContentDecision(true, ".doc", false));
    assertThat(registry.pipelineFor("vermerk.docx", DOCX)).isSameAs(fallback);
    assertThat(registry.supportedFormats().decideForFileName("vermerk.docx", DOCX).supported())
        .isFalse();
  }

  @Test
  void aFileThatCannotBeReadForDetectionFallsBackWithFormatDetectionFailedSet() {
    // Regression guard for #1165: a read failure (deleted, permission-denied, briefly
    // locked) must not be indistinguishable from a content decision that admits nothing -
    // DocumentIngestService relies on formatDetectionFailed() to avoid persisting a routing key
    // for a chunk this method never actually routed on content.
    DocumentFormatRegistry registry = registryWith();
    java.nio.file.Path missing =
        java.nio.file.Path.of("does-not-exist-" + java.util.UUID.randomUUID());

    DocumentFormatRegistry.Routed routed = registry.routedPipelineFor(missing, "bericht.pdf");

    assertThat(routed.pipeline()).isSameAs(fallback);
    assertThat(routed.detectedExtension()).isNull();
    assertThat(routed.formatDetectionFailed()).isTrue();
  }

  @Test
  void twoPipelinesClaimingTheSameFormatFailFast() {
    DocumentFormat first = new FakePipeline("pdf-a", (short) 1, Set.of(PDF_ADMISSION));
    DocumentFormat second = new FakePipeline("pdf-b", (short) 1, Set.of(PDF_ADMISSION));

    assertThatThrownBy(() -> registryWith(first, second))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(".pdf");
  }

  @Test
  void reportsEveryRegisteredPipelineWithItsCurrentVersion() {
    DocumentFormat pdfPipeline = new FakePipeline("pdf", (short) 3, Set.of(PDF_ADMISSION));

    assertThat(registryWith(pdfPipeline).pipelines())
        .extracting(DocumentFormat::id, DocumentFormat::version)
        .containsExactlyInAnyOrder(
            org.assertj.core.api.Assertions.tuple(TikaFallbackFormat.ID, (short) 1),
            org.assertj.core.api.Assertions.tuple("pdf", (short) 3));
  }
}
