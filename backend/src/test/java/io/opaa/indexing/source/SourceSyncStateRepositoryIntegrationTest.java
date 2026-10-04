package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.opaa.api.types.SystemRole;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceDocumentContext;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.SourceTypes;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The change cursors and folder markers of {@link SourceSyncState} against the Liquibase schema:
 * they survive a round trip through their {@code jsonb} columns, which stay {@code NULL} for a
 * connector without them; and the hierarchy query the file sync keeps unchanged folders with.
 */
@OpaaIntegrationTest
class SourceSyncStateRepositoryIntegrationTest {

  @Autowired private SourceSyncStateRepository repository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID userId;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Sync State IT', now(), ?, ?)",
        userId,
        "sync-state-it-" + userId,
        "sync-state-it-" + userId + "@example.com",
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Ablage",
                null,
                userId,
                SourceTypes.S3,
                null,
                "https://ablage.example",
                null,
                null,
                false));
  }

  @AfterEach
  void tearDown() {
    if (library != null) {
      jdbcTemplate.update("DELETE FROM documents WHERE library_id = ?", library.getId());
      jdbcTemplate.update("DELETE FROM source_sync_state WHERE library_id = ?", library.getId());
      libraryRepository.deleteById(library.getId());
    }
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
  }

  @Test
  void heldAndValidCursorsOfSeveralStreamsSurviveTheRoundTrip() {
    SourceSyncState state = new SourceSyncState(library.getId());
    state.beginFullSync(UUID.randomUUID());
    // jsonb sorts keys by length first, so this insertion order does not survive - nor need it
    Map<String, String> cursors = new LinkedHashMap<>();
    cursors.put("drive:10", "a");
    cursors.put("drive:1", "b");
    cursors.put("folder:x", "c");
    state.holdPendingChangeCursors(cursors);
    repository.save(state);

    SourceSyncState held = repository.findByLibraryId(library.getId()).orElseThrow();
    assertThat(held.pendingChangeCursors()).isEqualTo(cursors);
    assertThat(held.changeCursors()).isEmpty();

    held.completeFullSync(Instant.parse("2026-10-03T10:00:00Z"));
    repository.save(held);

    SourceSyncState completed = repository.findByLibraryId(library.getId()).orElseThrow();
    assertThat(completed.changeCursors()).isEqualTo(cursors);
    assertThat(completed.pendingChangeCursors()).isEmpty();
  }

  @Test
  void aConnectorWithoutAChangeLogLeavesTheColumnNull() {
    SourceSyncState state = new SourceSyncState(library.getId());
    state.beginFullSync(UUID.randomUUID());
    state.markScopeCompleted("dokumente/2025/");
    state.completeFullSync(Instant.parse("2026-10-03T10:00:00Z"));
    repository.save(state);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT change_cursors::text FROM source_sync_state WHERE library_id = ?",
                String.class,
                library.getId()))
        .isNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT subtree_markers::text FROM source_sync_state WHERE library_id = ?",
                String.class,
                library.getId()))
        .isNull();
  }

  @Test
  void rememberedFolderMarkersSurviveTheRoundTrip() {
    SourceSyncState state = new SourceSyncState(library.getId());
    SourceSyncState.SubtreeMemory memory =
        new SourceSyncState.SubtreeMemory(
            "v2|1024|pdf,txt",
            Instant.parse("2026-10-03T10:00:00Z"),
            Map.of("/Projekte", Map.of("", "\"6ac1\"", "Akten / 2026", "\"6ac2\"")));
    state.rememberSubtrees(memory);
    repository.save(state);

    assertThat(repository.findByLibraryId(library.getId()).orElseThrow().subtreeMemory())
        .isEqualTo(memory);
  }

  @Test
  void theHierarchyQueryFindsAFolderAndWhatLiesBelowItButNoNamesake() {
    saveDocument("1", "Projekte", "alt");
    saveDocument("2", "Projekte", "alt / tief");
    saveDocument("3", "Projekte", "altlasten");
    saveDocument("4", "Projekte", "a_t / x");
    saveDocument("5", "Projekte", "a%t");
    saveDocument("6", "Andere", "alt");
    saveDocument("7", "Projekte", null);

    assertThat(paths(documentRepository.findInHierarchy(library.getId(), "Projekte", "alt")))
        .containsExactlyInAnyOrder("1", "2");
    assertThat(paths(documentRepository.findInHierarchy(library.getId(), "Projekte", "a_t")))
        .as("a LIKE wildcard in the folder name matches only itself")
        .containsExactly("4");
    assertThat(
            paths(
                documentRepository.findByLibraryIdAndSourceContainerKey(
                    library.getId(), "Projekte")))
        .containsExactlyInAnyOrder("1", "2", "3", "4", "5", "7");
  }

  @Test
  void documentsAwaitingAVisitAreFoundByTheirFolder() {
    saveDocument("1", "Projekte", "alt");
    saveDocument("2", "Projekte", "neu");
    saveDocument("3", "Projekte", null);
    for (Document document :
        documentRepository.findByLibraryIdAndSourceContainerKey(library.getId(), "Projekte")) {
      if (!document.getFilePath().equals("2")) {
        document.setStatus(io.opaa.api.types.DocumentStatus.INDEXED);
        document.setLastModifiedRemote("m:1");
        documentRepository.save(document);
      }
    }
    documentRepository.markForReindexOnNextRun(
        documentRepository.findByLibraryIdAndFilePath(library.getId(), "1").orElseThrow().getId());

    assertThat(documentRepository.findHierarchyPathsAwaitingAVisit(library.getId(), "Projekte"))
        .as("1 is marked for the next run, 2 was never indexed, 3 is settled")
        .containsExactlyInAnyOrder("alt", "neu");
  }

  @Test
  void aRevisitIsNotedOnlyForALibraryWithAStateAndGoesWithTheState() {
    Instant now = Instant.parse("2026-10-04T10:00:00Z");
    assertThat(
            repository.recordRevisit(UUID.randomUUID(), library.getId(), "Projekte", "akten", now))
        .as("no state, no revisit")
        .isZero();
    SourceSyncState state = repository.save(new SourceSyncState(library.getId()));
    UUID first = UUID.randomUUID();
    String deep = "tief / ".repeat(400) + "ende";

    assertThat(repository.recordRevisit(first, library.getId(), "Projekte", deep, now)).isOne();
    assertThat(repository.recordRevisit(UUID.randomUUID(), library.getId(), "Projekte", null, now))
        .isOne();

    assertThat(repository.findRevisits(state.getId()))
        .extracting(
            SourceSyncStateRepository.Revisit::getContainerKey,
            SourceSyncStateRepository.Revisit::getHierarchyPath)
        .containsExactlyInAnyOrder(tuple("Projekte", deep), tuple("Projekte", null));
    assertThat(repository.deleteRevisits(List.of(first))).isOne();
    assertThat(repository.findRevisits(state.getId()))
        .extracting(SourceSyncStateRepository.Revisit::getHierarchyPath)
        .containsExactly((String) null);

    jdbcTemplate.update("DELETE FROM source_sync_state WHERE id = ?", state.getId());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM source_sync_revisits WHERE sync_state_id = ?",
                Long.class,
                state.getId()))
        .isZero();
  }

  private void saveDocument(String filePath, String containerKey, String hierarchyPath) {
    Document document = new Document(filePath, filePath, "text/plain", 1L, SourceTypes.S3);
    document.setLibraryId(library.getId());
    document.setOrganizationId(Organization.DEFAULT_ID);
    document.applySourceContext(new SourceDocumentContext(containerKey, hierarchyPath));
    documentRepository.save(document);
  }

  private static List<String> paths(List<Document> documents) {
    return documents.stream().map(Document::getFilePath).toList();
  }
}
