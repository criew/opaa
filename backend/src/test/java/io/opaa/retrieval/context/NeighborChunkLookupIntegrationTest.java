package io.opaa.retrieval.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link NeighborChunkLookup} against a real Postgres: it loads exactly the requested positions,
 * and never one whose library lies outside the scope it is given.
 */
@OpaaIntegrationTest
class NeighborChunkLookupIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private NeighborChunkLookup lookup;

  private final UUID library = UUID.randomUUID();
  private final UUID foreignLibrary = UUID.randomUUID();
  private final String document = UUID.randomUUID().toString();
  private final String otherDocument = UUID.randomUUID().toString();
  private final List<UUID> chunkIds = new ArrayList<>();

  @AfterEach
  void tearDown() {
    chunkIds.forEach(id -> jdbcTemplate.update("DELETE FROM vector_store WHERE id = ?", id));
  }

  @Test
  void loadsExactlyTheRequestedPositionsWithTheirTextAndMetadata() {
    insert(document, 0, library, "Abschnitt 0");
    insert(document, 1, library, "Abschnitt 1");
    insert(document, 2, library, "Abschnitt 2");
    insert(otherDocument, 1, library, "anderes Dokument");

    Map<ChunkPosition, Document> found =
        lookup.find(
            List.of(new ChunkPosition(document, 0), new ChunkPosition(document, 2)),
            Set.of(library));

    assertThat(found)
        .containsOnlyKeys(new ChunkPosition(document, 0), new ChunkPosition(document, 2));
    Document chunk = found.get(new ChunkPosition(document, 2));
    assertThat(chunk.getText()).isEqualTo("Abschnitt 2");
    assertThat(chunk.getMetadata())
        .containsEntry("document_id", document)
        .containsEntry("file_name", "satzung.pdf");
  }

  @Test
  void neverLoadsAPositionWhoseLibraryIsOutsideTheScope() {
    insert(document, 1, foreignLibrary, "fremde Bibliothek");

    assertThat(lookup.find(List.of(new ChunkPosition(document, 1)), Set.of(library))).isEmpty();
    assertThat(lookup.find(List.of(new ChunkPosition(document, 1)), Set.of())).isEmpty();
  }

  @Test
  void aPositionPastTheEndOfItsDocumentIsSimplyAbsent() {
    insert(document, 0, library, "Abschnitt 0");

    assertThat(lookup.find(List.of(new ChunkPosition(document, 1)), Set.of(library))).isEmpty();
  }

  private void insert(String documentId, int chunkIndex, UUID libraryId, String content) {
    UUID id = UUID.randomUUID();
    chunkIds.add(id);
    String metadata =
        "{\"document_id\":\"%s\",\"chunk_index\":%d,\"library_id\":\"%s\",\"file_name\":\"satzung.pdf\"}"
            .formatted(documentId, chunkIndex, libraryId);
    jdbcTemplate.update(
        "INSERT INTO vector_store (id, content, metadata) VALUES (?, ?, ?::jsonb)",
        id,
        content,
        metadata);
  }
}
