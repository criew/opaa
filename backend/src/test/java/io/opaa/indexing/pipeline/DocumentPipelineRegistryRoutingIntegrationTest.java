package io.opaa.indexing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.mail.ChunkMailMetadata;
import io.opaa.indexing.pipeline.markdown.MarkdownDocumentPipeline;
import io.opaa.test.OpaaIndexingIntegrationTest;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * Pins the routing matrix {@link DocumentPipelineRegistry} actually resolves in a real, fully
 * Spring-wired {@link ApplicationContext} - every {@link DocumentPipeline} bean {@link
 * IndexingConfiguration} registers, not the hand-picked fakes {@link DocumentPipelineRegistryTest}
 * uses to cover the routing algorithm itself (docs/features/ingestion-pipelines.md, Teil 1). A bean
 * wiring mistake (a pipeline never registered, or registered under the wrong format) would
 * otherwise only surface as a behavioural change in an end-to-end indexing test, not as a routing
 * assertion of its own. Reuses {@code @OpaaIndexingIntegrationTest} verbatim - no class-local
 * {@code @DynamicPropertySource}/{@code @Import}/{@code @MockitoBean} - so this class shares the
 * cached context with every other class carrying that same signature (AGENTS.md,
 * Spring-Testkontexte).
 */
@OpaaIndexingIntegrationTest
class DocumentPipelineRegistryRoutingIntegrationTest {

  @Autowired private DocumentPipelineRegistry registry;
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

    // Every format without its own registered pipeline keeps going through TikaFallbackPipeline -
    // the verhaltensneutral guarantee of Teil 1 (a new pipeline bean must never change this for a
    // format it does not claim).
    assertThat(registry.pipelineFor("notiz.md", "text/plain").id()).isEqualTo("tika-fallback");
    assertThat(registry.pipelineFor("notiz.txt", "text/plain").id()).isEqualTo("tika-fallback");
    assertThat(registry.pipelineFor("altakte.doc", "application/msword").id())
        .isEqualTo("tika-fallback");
    assertThat(registry.pipelineFor("satzung.odt", "application/vnd.oasis.opendocument.text").id())
        .isEqualTo("tika-fallback");
    assertThat(
            registry
                .pipelineFor("vortrag.odp", "application/vnd.oasis.opendocument.presentation")
                .id())
        .isEqualTo("tika-fallback");
  }

  @Test
  void markdownDocumentPipelineIsDeliberatelyNotRegisteredAsABean() {
    // #1103: MarkdownDocumentPipeline is built and tested but not wired into
    // IndexingConfiguration - registering it would silently change the chunk shape of the entire
    // eval corpus (Markdown), see ingestion-pipelines.md's "Umgesetzt (#1061)" section. Asserting
    // the bean's absence, not just that routing falls back for .md above, catches a regression even
    // if a future change happened to keep the fallback routing correct by coincidence.
    assertThatThrownBy(() -> applicationContext.getBean(MarkdownDocumentPipeline.class))
        .isInstanceOf(NoSuchBeanDefinitionException.class);
  }

  /**
   * Every pipeline actually wired into the application declares exactly the passthrough metadata
   * keys it set before the hardcoded {@code storeChunks} allowlist was replaced by {@link
   * DocumentPipeline#passthroughMetadataKeys()} - a single parametrized guard against declaration
   * drift, replacing what used to be one near-identical unit test per pipeline class (each of them
   * asserting the declaration against itself, not against a shared expectation).
   */
  @Test
  void everyPipelineDeclaresExactlyItsOwnMetadataKeys() {
    Map<String, Set<String>> expectedByPipelineId =
        Map.of(
            "pdf", Set.of(ChunkingService.LOCATION_METADATA_KEY),
            "docx", Set.of(ChunkingService.LOCATION_METADATA_KEY),
            "pptx", Set.of(ChunkingService.LOCATION_METADATA_KEY),
            "tabular", Set.of(ChunkingService.LOCATION_METADATA_KEY),
            "html", Set.of(ChunkingService.LOCATION_METADATA_KEY),
            "tika-fallback", Set.of(ChunkingService.LOCATION_METADATA_KEY),
            "email",
                Set.of(
                    ChunkingService.LOCATION_METADATA_KEY,
                    ChunkMailMetadata.MAIL_FROM_METADATA_KEY,
                    ChunkMailMetadata.MAIL_TO_METADATA_KEY,
                    ChunkMailMetadata.MAIL_SUBJECT_METADATA_KEY,
                    ChunkMailMetadata.MAIL_DATE_METADATA_KEY));

    assertThat(registry.pipelines())
        .extracting(DocumentPipeline::id)
        .containsExactlyInAnyOrderElementsOf(expectedByPipelineId.keySet());
    for (DocumentPipeline pipeline : registry.pipelines()) {
      assertThat(pipeline.passthroughMetadataKeys())
          .as("passthroughMetadataKeys() of pipeline %s", pipeline.id())
          .isEqualTo(expectedByPipelineId.get(pipeline.id()));
    }
  }
}
