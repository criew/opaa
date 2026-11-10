package io.opaa.retrieval.context;

import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.knowledge.VectorTable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads chunks by position straight out of the pgvector table, in one query. The search scope is
 * part of that query ({@code library_id = ANY(?)}), exactly as in both search paths, so a position
 * outside the caller's libraries is never loaded. Schema and table name come from the same
 * properties as in {@code ChunkEmbeddingLookup}.
 */
@Component
public class NeighborChunkLookup {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final VectorTable vectorTable;

  NeighborChunkLookup(
      JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, VectorTable vectorTable) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.vectorTable = vectorTable;
  }

  /**
   * The stored chunks at {@code positions} within {@code libraryIds}, keyed by position. A position
   * without a row - past the end of its document, or outside the scope - is absent from the result.
   * An empty scope loads nothing.
   */
  public Map<ChunkPosition, Document> find(
      Collection<ChunkPosition> positions, Set<UUID> libraryIds) {
    if (positions.isEmpty() || libraryIds.isEmpty()) {
      return Map.of();
    }
    Set<String> documentIds = new LinkedHashSet<>();
    Map<String, ChunkPosition> byKey = new HashMap<>();
    for (ChunkPosition position : positions) {
      documentIds.add(position.documentId());
      byKey.put(position.key(), position);
    }
    String documentIdKey = VectorChunkStore.DOCUMENT_ID_METADATA_KEY;
    String sql =
        "SELECT id, content, metadata FROM "
            + vectorTable.qualifiedName()
            + " WHERE metadata->>'"
            + documentIdKey
            + "' = ANY(?)"
            + " AND metadata->>'"
            + VectorChunkStore.LIBRARY_ID_METADATA_KEY
            + "' = ANY(?)"
            + " AND (metadata->>'"
            + documentIdKey
            + "') || '#' || (metadata->>'"
            + VectorChunkStore.CHUNK_INDEX_METADATA_KEY
            + "') = ANY(?)";
    String[] libraries = libraryIds.stream().map(UUID::toString).toArray(String[]::new);
    Map<ChunkPosition, Document> result = new HashMap<>();
    jdbcTemplate.query(
        sql,
        ps -> {
          ps.setArray(1, ps.getConnection().createArrayOf("text", documentIds.toArray()));
          ps.setArray(2, ps.getConnection().createArrayOf("text", libraries));
          ps.setArray(3, ps.getConnection().createArrayOf("text", byKey.keySet().toArray()));
        },
        (RowCallbackHandler)
            rs -> {
              Document chunk =
                  Document.builder()
                      .id(rs.getString("id"))
                      .text(rs.getString("content"))
                      .metadata(readMetadata(rs.getString("metadata")))
                      .build();
              ChunkPosition.of(chunk)
                  .map(position -> byKey.get(position.key()))
                  .ifPresent(position -> result.put(position, chunk));
            });
    return result;
  }

  /**
   * The stored chunks of {@code documentId} from {@code fromIndex} to {@code toIndex} (both
   * inclusive) within {@code libraryIds}, in index order. A document outside the scope loads
   * nothing; an empty scope loads nothing.
   */
  public List<Document> findRange(
      String documentId, int fromIndex, int toIndex, Set<UUID> libraryIds) {
    if (documentId == null || libraryIds.isEmpty() || toIndex < fromIndex) {
      return List.of();
    }
    String indexText = "metadata->>'" + VectorChunkStore.CHUNK_INDEX_METADATA_KEY + "'";
    // The guard keeps a non-integral index of any row from failing the cast.
    String chunkIndex =
        "(CASE WHEN " + indexText + " ~ '^[0-9]{1,9}$' THEN (" + indexText + ")::int END)";
    String sql =
        "SELECT id, content, metadata FROM "
            + vectorTable.qualifiedName()
            + " WHERE metadata->>'"
            + VectorChunkStore.DOCUMENT_ID_METADATA_KEY
            + "' = ?"
            + " AND metadata->>'"
            + VectorChunkStore.LIBRARY_ID_METADATA_KEY
            + "' = ANY(?)"
            + " AND "
            + chunkIndex
            + " BETWEEN ? AND ?"
            + " ORDER BY "
            + chunkIndex;
    String[] libraries = libraryIds.stream().map(UUID::toString).toArray(String[]::new);
    List<Document> result = new ArrayList<>();
    jdbcTemplate.query(
        sql,
        ps -> {
          ps.setString(1, documentId);
          ps.setArray(2, ps.getConnection().createArrayOf("text", libraries));
          ps.setInt(3, fromIndex);
          ps.setInt(4, toIndex);
        },
        (RowCallbackHandler)
            rs -> {
              result.add(
                  Document.builder()
                      .id(rs.getString("id"))
                      .text(rs.getString("content"))
                      .metadata(readMetadata(rs.getString("metadata")))
                      .build());
            });
    return result;
  }

  /** Null-valued keys are dropped: {@link Document} rejects them. */
  private Map<String, Object> readMetadata(String metadataJson) {
    if (metadataJson == null || metadataJson.isBlank()) {
      return Map.of();
    }
    Map<String, Object> metadata =
        objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
    metadata.values().removeIf(Objects::isNull);
    return metadata;
  }
}
