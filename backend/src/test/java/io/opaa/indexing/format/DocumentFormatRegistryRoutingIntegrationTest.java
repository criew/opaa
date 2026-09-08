package io.opaa.indexing.format;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.chunk.ChunkingService;
import io.opaa.indexing.format.file.markdown.MarkdownDocumentFormat;
import io.opaa.test.OpaaIndexingIntegrationTest;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * Pins the routing matrix {@link DocumentFormatRegistry} actually resolves in a real, fully
 * Spring-wired {@link ApplicationContext} - every {@link DocumentFormat} bean {@link
 * IndexingConfiguration} registers, not the hand-picked fakes {@link DocumentFormatRegistryTest}
 * uses to cover the routing algorithm itself (docs/features/ingestion-pipelines.md, Teil 1). A bean
 * wiring mistake (a pipeline never registered, or registered under the wrong format) would
 * otherwise only surface as a behavioural change in an end-to-end indexing test, not as a routing
 * assertion of its own. Reuses {@code @OpaaIndexingIntegrationTest} verbatim - no class-local
 * {@code @DynamicPropertySource}/{@code @Import}/{@code @MockitoBean} - so this class shares the
 * cached context with every other class carrying that same signature (AGENTS.md,
 * Spring-Testkontexte).
 */
@OpaaIndexingIntegrationTest
class DocumentFormatRegistryRoutingIntegrationTest {

  @Autowired private DocumentFormatRegistry registry;
  @Autowired private ApplicationContext applicationContext;

  @Test
  void routesEveryAdmittedFormatToItsRegisteredPipeline() {
    assertThat(registry.pipelineFor("satzung.pdf", "application/pdf").id()).isEqualTo("pdf");
    assertThat(
            registry
                .pipelineFor(
                    "vermerk.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .id())
        .isEqualTo("docx");
    assertThat(
            registry
                .pipelineFor(
                    "vortrag.pptx",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation")
                .id())
        .isEqualTo("pptx");
    assertThat(
            registry
                .pipelineFor(
                    "haushalt.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .id())
        .isEqualTo("tabular");
    assertThat(registry.pipelineFor("zustaendigkeiten.csv", "text/plain").id())
        .isEqualTo("tabular");
    assertThat(
            registry
                .pipelineFor("haushalt.ods", "application/vnd.oasis.opendocument.spreadsheet")
                .id())
        .isEqualTo("tabular");
    assertThat(registry.pipelineFor("buergeramt.html", "text/html").id()).isEqualTo("html");
    assertThat(registry.pipelineFor("vorgang.eml", "text/plain").id()).isEqualTo("email");
    assertThat(registry.pipelineFor("vorgang.msg", "application/vnd.ms-outlook").id())
        .isEqualTo("email");
    assertThat(registry.pipelineFor("satzung.odt", "application/vnd.oasis.opendocument.text").id())
        .isEqualTo("odt");
    assertThat(
            registry
                .pipelineFor("vortrag.odp", "application/vnd.oasis.opendocument.presentation")
                .id())
        .isEqualTo("odp");

    // Markdown is claimed by its own pipeline now, unlike every other format without one
    // registered, which keeps going through TikaFallbackFormat - the verhaltensneutral guarantee
    // of Teil 1 (a new pipeline bean must never change this for a format it does not claim).
    assertThat(registry.pipelineFor("notiz.md", "text/plain").id()).isEqualTo("markdown");
    assertThat(registry.pipelineFor("notiz.txt", "text/plain").id()).isEqualTo("tika-fallback");
    assertThat(registry.pipelineFor("altakte.doc", "application/msword").id())
        .isEqualTo("tika-fallback");
  }

  @Test
  void markdownDocumentPipelineIsRegisteredAsABean() {
    // MarkdownDocumentFormat is registered like every other format pipeline, replacing
    // TikaFallbackFormat for .md - asserting the bean's presence, not just the routing decision
    // above, catches a regression even if a future change happened to keep the routing correct by
    // coincidence (e.g. a second, competing bean that also claims ".md").
    assertThat(applicationContext.getBean(MarkdownDocumentFormat.class)).isNotNull();
  }

  /**
   * {@link DocumentFormat#id()} and {@link DocumentFormat#version()} are persisted on every chunk
   * ({@link ChunkFormatMetadata}) and select what a re-index pulls in, so both are part of the
   * stored corpus rather than of the Java type. Pinned against literals here, not against the
   * classes' own constants, so that renaming or moving a format class cannot silently take a stored
   * value with it.
   */
  @Test
  void everyFormatKeepsItsPersistedIdAndVersion() {
    Map<String, Short> expectedVersionById =
        Map.ofEntries(
            Map.entry("pdf", (short) 1),
            Map.entry("docx", (short) 3),
            Map.entry("pptx", (short) 1),
            Map.entry("odt", (short) 2),
            Map.entry("odp", (short) 2),
            Map.entry("tabular", (short) 1),
            Map.entry("markdown", (short) 1),
            Map.entry("html", (short) 3),
            Map.entry("email", (short) 5),
            Map.entry("tika-fallback", (short) 1),
            Map.entry("confluence", (short) 2));

    assertThat(registry.pipelines())
        .extracting(DocumentFormat::id)
        .containsExactlyInAnyOrderElementsOf(expectedVersionById.keySet());
    for (DocumentFormat pipeline : registry.pipelines()) {
      assertThat(pipeline.version())
          .as("version() of pipeline %s", pipeline.id())
          .isEqualTo(expectedVersionById.get(pipeline.id()));
    }
  }

  /**
   * Every pipeline actually wired into the application declares exactly the passthrough metadata
   * keys it set before the hardcoded {@code storeChunks} allowlist was replaced by {@link
   * DocumentFormat#passthroughMetadataKeys()} - a single parametrized guard against declaration
   * drift, instead of one near-identical unit test per pipeline class (each of them asserting the
   * declaration against itself, not against a shared expectation).
   */
  @Test
  void everyPipelineDeclaresExactlyItsOwnMetadataKeys() {
    Map<String, Set<String>> expectedByPipelineId =
        Map.ofEntries(
            Map.entry("pdf", Set.of(ChunkingService.LOCATION_METADATA_KEY)),
            Map.entry("docx", Set.of(ChunkingService.LOCATION_METADATA_KEY)),
            Map.entry("pptx", Set.of(ChunkingService.LOCATION_METADATA_KEY)),
            Map.entry("tabular", Set.of(ChunkingService.LOCATION_METADATA_KEY)),
            Map.entry("html", Set.of(ChunkingService.LOCATION_METADATA_KEY)),
            Map.entry("tika-fallback", Set.of(ChunkingService.LOCATION_METADATA_KEY)),
            Map.entry("odt", Set.of(ChunkingService.LOCATION_METADATA_KEY)),
            Map.entry("odp", Set.of(ChunkingService.LOCATION_METADATA_KEY)),
            Map.entry("markdown", Set.of(ChunkingService.LOCATION_METADATA_KEY)),
            Map.entry(
                "confluence",
                Set.of(
                    ChunkingService.LOCATION_METADATA_KEY,
                    ChunkingService.SOURCE_CONTAINER_METADATA_KEY,
                    ChunkingService.SOURCE_HIERARCHY_METADATA_KEY)),
            Map.entry("email", Set.of(ChunkingService.LOCATION_METADATA_KEY)));

    assertThat(registry.pipelines())
        .extracting(DocumentFormat::id)
        .containsExactlyInAnyOrderElementsOf(expectedByPipelineId.keySet());
    for (DocumentFormat pipeline : registry.pipelines()) {
      assertThat(pipeline.passthroughMetadataKeys())
          .as("passthroughMetadataKeys() of pipeline %s", pipeline.id())
          .isEqualTo(expectedByPipelineId.get(pipeline.id()));
    }
  }
}
