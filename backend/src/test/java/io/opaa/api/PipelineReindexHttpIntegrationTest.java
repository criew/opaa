package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.pipeline.ChunkPipelineMetadata;
import io.opaa.indexing.pipeline.TikaFallbackPipeline;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.UploadProperties;
import io.opaa.organization.Organization;
import io.opaa.test.EmbeddingModelFakeConfiguration;
import io.opaa.test.OpaaMockMvcTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@code POST /api/v1/admin/indexing/pipeline-reindex} end to end (#1109, Epic #1054/#1110 review,
 * E4): {@link IndexingAdminControllerTest} mocks {@link io.opaa.indexing.PipelineReindexService}
 * out entirely, so nothing ever proved the real HTTP request actually reaches the real service and
 * mutates real chunks - only that the controller forwards whatever a stub happens to return.
 */
// Own EmbeddingModel fake, unlike every other @OpaaMockMvcTest class: this is the one HTTP-level
// test that actually runs a real re-index batch (PipelineReindexService re-embeds every rewritten
// chunk), which would otherwise dial the real, unreachable-in-CI Ollama endpoint
// (application.yml's default spring.ai.openai.embedding.base-url). Real HTTP client instead of
// MockMvc against @OpaaIndexingIntegrationTest was evaluated and rejected: Spring Boot 4's
// TestRestTemplate needs its own opt-in @AutoConfigureTestRestTemplate, which would recreate the
// same per-class context split this comment already documents, just for a different bean. Own
// Spring context per AGENTS.md "Spring-Testkontexte"; EmbeddingModelFakeConfiguration is a shared,
// top-level class in io.opaa.test so a second @OpaaMockMvcTest class with the same need shares this
// context instead of each declaring its own class-local equivalent.
@OpaaMockMvcTest
@Import(EmbeddingModelFakeConfiguration.class)
class PipelineReindexHttpIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private org.springframework.ai.vectorstore.VectorStore vectorStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private UploadProperties uploadProperties;

  private User devAdmin;
  private KnowledgeLibrary library;
  private Document document;

  private RequestPostProcessor devUser() {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, "dev-admin");
      return request;
    };
  }

  @BeforeEach
  void setUp() throws Exception {
    // Provisions "dev-admin" as SYSTEM_ADMIN via the real UserProvisioningFilter, mirroring
    // AdminControllerOrganizationBoundaryIntegrationTest/LibraryIndexingAuthorizationIntegrationTest.
    mockMvc.perform(get("/api/v1/admin/indexing/pipeline-versions").with(devUser()));
    devAdmin =
        userRepository.findAll().stream()
            .filter(u -> "admin@opaa.local".equals(u.getEmail()))
            .findFirst()
            .orElseThrow();

    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Reindex-HTTP-Testbibliothek",
                null,
                devAdmin.getId(),
                LibraryVisibility.PRIVATE,
                false));

    Path managedDirectory =
        Path.of(uploadProperties.storagePath()).resolve(library.getId().toString());
    Files.createDirectories(managedDirectory);
    Path storedFile = managedDirectory.resolve(UUID.randomUUID() + "-vermerk.txt");
    Files.writeString(storedFile, "Ein Vermerk ueber Verwaltungsgebuehren. ".repeat(20));

    document =
        new Document(
            "vermerk.txt",
            storedFile.toAbsolutePath().toString(),
            "text/plain",
            1024L,
            DocumentSourceType.UPLOAD);
    document.setLibraryId(library.getId());
    document.setOrganizationId(Organization.DEFAULT_ID);
    document.setChecksum("checksum-vermerk");
    document = documentRepository.save(document);

    Map<String, Object> metadata = new HashMap<>();
    metadata.put(VectorChunkStore.DOCUMENT_ID_METADATA_KEY, document.getId().toString());
    metadata.put(VectorChunkStore.LIBRARY_ID_METADATA_KEY, library.getId().toString());
    metadata.put("organization_id", Organization.DEFAULT_ID.toString());
    // No pipeline_id/pipeline_version at all - the pre-abstraction state PipelineReindexService's
    // own contract (see PipelineReindexServiceIntegrationTest) treats as stale for every pipeline.
    vectorStore.add(
        List.of(new org.springframework.ai.document.Document("veralteter chunk", metadata)));
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update(
        "DELETE FROM vector_store WHERE metadata->>'document_id' = ?", document.getId().toString());
    jdbcTemplate.update("DELETE FROM chunk_full_text WHERE document_id = ?", document.getId());
    documentRepository.deleteById(document.getId());
    jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE id = ?", library.getId());
  }

  @Test
  void theRealEndpointReindexesThroughTheRealServiceAndPersistsTheNewChunk() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/indexing/pipeline-reindex")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"pipelineId\":\""
                        + TikaFallbackPipeline.ID
                        + "\",\"belowVersion\":"
                        + TikaFallbackPipeline.VERSION
                        + ",\"batchSize\":10}")
                .with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reindexedDocuments").value(1))
        .andExpect(jsonPath("$.skippedDocuments").value(0))
        // "done" means the backlog is drained (PipelineReindexResult#isEmpty) - false here since
        // this very call is the batch that did the work, not a following no-op call.
        .andExpect(jsonPath("$.done").value(false));

    List<String> chunkTexts =
        jdbcTemplate.queryForList(
            "SELECT content FROM vector_store WHERE metadata->>'document_id' = ?",
            String.class,
            document.getId().toString());
    // The old, pre-abstraction chunk is gone; a real chunk read from the file on disk replaced it
    // (not merely a response field the controller could have fabricated on its own).
    assertThat(chunkTexts).isNotEmpty().noneMatch("veralteter chunk"::equals);

    List<String> pipelineIds =
        jdbcTemplate.queryForList(
            "SELECT metadata->>'"
                + ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY
                + "' FROM vector_store WHERE metadata->>'document_id' = ?",
            String.class,
            document.getId().toString());
    assertThat(pipelineIds).containsOnly(TikaFallbackPipeline.ID);
  }

  // IndexingAdminControllerTest already covers "unknown pipelineId" and "belowVersion above the
  // pipeline's own version" against a mocked DocumentPipelineRegistry with a fabricated version
  // number. What that mock cannot prove is that the guard engages with the *real*
  // TikaFallbackPipeline.VERSION wired up in production - a real registry bean whose version this
  // test does not control could drift out of sync with a mock's hardcoded stand-in without either
  // test noticing. This test exercises exactly that: an actually registered pipeline id with a
  // belowVersion one above its real, current version.
  @Test
  void aBelowVersionAboveTheRealPipelinesOwnVersionIsRejectedBeforeTouchingTheRealService()
      throws Exception {
    int belowVersion = TikaFallbackPipeline.VERSION + 1;
    mockMvc
        .perform(
            post("/api/v1/admin/indexing/pipeline-reindex")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"pipelineId\":\""
                        + TikaFallbackPipeline.ID
                        + "\",\"belowVersion\":"
                        + belowVersion
                        + "}")
                .with(devUser()))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error")
                .value(
                    "belowVersion darf höchstens der aktuellen Version der Pipeline "
                        + TikaFallbackPipeline.ID
                        + " entsprechen ("
                        + TikaFallbackPipeline.VERSION
                        + "), war "
                        + belowVersion));

    // The seeded chunk survives untouched - a validation failure must never reach the real
    // service and rewrite (or delete) anything.
    List<String> chunkTexts =
        jdbcTemplate.queryForList(
            "SELECT content FROM vector_store WHERE metadata->>'document_id' = ?",
            String.class,
            document.getId().toString());
    assertThat(chunkTexts).containsExactly("veralteter chunk");
  }
}
