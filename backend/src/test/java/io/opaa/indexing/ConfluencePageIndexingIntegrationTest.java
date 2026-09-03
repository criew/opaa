package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.pipeline.ChunkPipelineMetadata;
import io.opaa.indexing.pipeline.confluence.ConfluenceDocumentPipeline;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIndexingIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The end-to-end proof for #1137 that a representative Confluence page - macros, a table, a
 * hierarchy - is answerable after chunking: the storage body goes through {@link
 * FileProcessingService#processConfluencePage}, is cut by {@link ConfluenceDocumentPipeline}, and
 * the stored chunks carry the pipeline id, the section as Fundort, the space and hierarchy path,
 * and the table row a question about the deadline would hit. Recorded as a test, not as a manual
 * spot check; the fake embedding model ties every vector, so retrieval is asserted at threshold 0,
 * as in {@code DocumentIndexingIntegrationTest}.
 */
@OpaaIndexingIntegrationTest
class ConfluencePageIndexingIntegrationTest {

  private static final String PAGE_URL =
      "https://wiki.behoerde.example/confluence/pages/viewpage.action?pageId=102";

  @Autowired private FileProcessingService fileProcessingService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private VectorStore vectorStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private KnowledgeLibraryRepository libraryRepository;

  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text, chunk_full_text_skip");
    documentRepository.deleteAll();
    UUID owner = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Confluence IT', now(), 'USER', ?)",
        owner,
        "confluence-it-" + owner,
        "confluence-it-" + owner + "@example.com",
        Organization.DEFAULT_ID);
    library =
        KnowledgeLibrary.ownedByUser(
            Organization.DEFAULT_ID,
            "Wiki Bauamt " + owner,
            null,
            owner,
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.CONFLUENCE,
            null,
            "https://wiki.behoerde.example/confluence",
            null,
            "enc:v1:token",
            false);
    library.configureConfluence(
        io.opaa.api.types.ConfluenceEdition.DATA_CENTER,
        List.of(new io.opaa.library.ConfluenceSpaceSelection("ENG", "Engineering")));
    library = libraryRepository.save(library);
  }

  @Test
  void aRepresentativePageIsAnswerableAfterChunkingWithItsSectionSpaceAndHierarchy() {
    String storageBody =
        "<ac:structured-macro ac:name=\"toc\"/>"
            + "<h1>Zuständigkeiten</h1>"
            + "<p>Das Bauamt bearbeitet Anträge innerhalb von 14 Tagen.</p>"
            + "<table><tbody><tr><th>Vorgang</th><th>Frist</th></tr>"
            + "<tr><td>Bauantrag</td><td>14 Tage</td></tr></tbody></table>"
            + "<h1>Unterlagen</h1>"
            + "<ac:structured-macro ac:name=\"info\"><ac:parameter ac:name=\"title\">Hinweis"
            + "</ac:parameter><ac:rich-text-body><p>Die Frist beginnt mit dem Eingang.</p>"
            + "</ac:rich-text-body></ac:structured-macro>"
            + "<ac:structured-macro ac:name=\"jira\"><ac:parameter ac:name=\"jqlQuery\">project ="
            + " BAU</ac:parameter></ac:structured-macro>";

    FileProcessingResult result =
        fileProcessingService.processConfluencePage(
            storageBody,
            "Abschnitt 1.1",
            PAGE_URL,
            "3",
            new SourceDocumentContext("ENG", "Handbuch / Kapitel 1"),
            library);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    Document document =
        documentRepository.findByLibraryIdAndFilePath(library.getId(), PAGE_URL).orElseThrow();
    assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(document.getChunkCount()).isEqualTo(2);
    assertThat(document.getSourceContainerKey()).isEqualTo("ENG");
    assertThat(document.getSourceHierarchyPath()).isEqualTo("Handbuch / Kapitel 1");
    assertThat(document.getLastModifiedRemote()).isEqualTo("3");

    List<org.springframework.ai.document.Document> hits =
        vectorStore.similaritySearch(
            SearchRequest.builder()
                .query("Wie lange dauert ein Bauantrag?")
                .topK(10)
                .similarityThreshold(0.0)
                .build());
    assertThat(hits).hasSize(2);
    assertThat(hits)
        .allMatch(
            hit ->
                ConfluenceDocumentPipeline.ID.equals(
                    hit.getMetadata().get(ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY)));
    assertThat(hits)
        .allMatch(
            hit ->
                "ENG".equals(hit.getMetadata().get(ConfluenceDocumentPipeline.SPACE_METADATA_KEY)));
    assertThat(hits)
        .allMatch(
            hit ->
                "Handbuch / Kapitel 1 / Abschnitt 1.1"
                    .equals(
                        hit.getMetadata().get(ConfluenceDocumentPipeline.HIERARCHY_METADATA_KEY)));
    org.springframework.ai.document.Document deadline =
        hits.stream()
            .filter(hit -> hit.getText().contains("Bauantrag | 14 Tage"))
            .findFirst()
            .orElseThrow();
    assertThat(deadline.getMetadata())
        .containsEntry(ChunkingService.LOCATION_METADATA_KEY, "Abschn. Zuständigkeiten");
    assertThat(deadline.getText()).startsWith("Zuständigkeiten\n\n");
    String all =
        String.join(
            "\n", hits.stream().map(org.springframework.ai.document.Document::getText).toList());
    assertThat(all).contains("Hinweis").contains("Die Frist beginnt mit dem Eingang.");
    assertThat(all).doesNotContain("project = BAU").doesNotContain("jqlQuery");
  }
}
