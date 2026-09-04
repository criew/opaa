package io.opaa.searchadmin;

import io.opaa.common.NotFoundException;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.library.KnowledgeLibraryRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads stored chunks straight out of the pgvector table for the administration page (#1230) - text
 * and metadata only, the embedding column is never selected.
 *
 * <p>The organization boundary is checked against the {@code documents} table, never against the
 * chunk's own {@code organization_id} metadatum: older chunks may lack it, and a chunk whose
 * document no longer exists is treated as absent. Schema/table name come from the same {@code
 * spring.ai.vectorstore.pgvector.*} properties {@code PgVectorStore} binds, as in {@code
 * ChunkEmbeddingLookup}.
 */
@Service
public class ChunkInspectionService {

  private static final String DOCUMENT_ID_KEY = VectorChunkStore.DOCUMENT_ID_METADATA_KEY;
  private static final String CHUNK_INDEX_KEY = "chunk_index";

  private final JdbcTemplate jdbcTemplate;
  private final DocumentRepository documentRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final ObjectMapper objectMapper;
  private final String selectSql;

  public ChunkInspectionService(
      JdbcTemplate jdbcTemplate,
      DocumentRepository documentRepository,
      KnowledgeLibraryRepository libraryRepository,
      ObjectMapper objectMapper,
      @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
    this.jdbcTemplate = jdbcTemplate;
    this.documentRepository = documentRepository;
    this.libraryRepository = libraryRepository;
    this.objectMapper = objectMapper;
    this.selectSql = "SELECT id, content, metadata FROM " + schemaName + "." + tableName;
  }

  /**
   * The chunk with the given id, or empty when the id is no UUID, no such row exists, its {@code
   * document_id} does not resolve to a document, or that document belongs to another organization.
   * The id is bound as a typed {@code uuid} so the primary-key index is used.
   */
  public Optional<ChunkInspection> findChunk(UUID organizationId, String chunkId) {
    UUID id;
    try {
      id = UUID.fromString(chunkId);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    List<StoredChunk> rows =
        jdbcTemplate.query(selectSql + " WHERE id = ?", this::toStoredChunk, id);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    StoredChunk chunk = rows.get(0);
    return chunk
        .documentId()
        .flatMap(documentRepository::findById)
        .filter(document -> organizationId.equals(document.getOrganizationId()))
        .map(document -> describe(chunk, document, libraryName(document)));
  }

  /**
   * Every stored chunk of the document ordered by {@code chunk_index}; a chunk without one sorts
   * last. Throws {@link NotFoundException} when the document does not exist or belongs to another
   * organization - the same answer for both, so the endpoint never confirms a foreign id.
   */
  public DocumentChunks listDocumentChunks(UUID organizationId, UUID documentId) {
    Document document =
        documentRepository
            .findById(documentId)
            .filter(candidate -> organizationId.equals(candidate.getOrganizationId()))
            .orElseThrow(() -> new NotFoundException("Das Dokument wurde nicht gefunden."));
    String libraryName = libraryName(document);
    List<ChunkInspection> chunks =
        jdbcTemplate
            .query(
                selectSql
                    + " WHERE metadata->>'"
                    + DOCUMENT_ID_KEY
                    + "' = ? ORDER BY (metadata->>'"
                    + CHUNK_INDEX_KEY
                    + "')::integer NULLS LAST, id",
                this::toStoredChunk,
                documentId.toString())
            .stream()
            .map(chunk -> describe(chunk, document, libraryName))
            .toList();
    return new DocumentChunks(
        document.getId(),
        document.getFileName(),
        document.getLibraryId(),
        libraryName,
        document.getChunkCount(),
        chunks);
  }

  private String libraryName(Document document) {
    if (document.getLibraryId() == null) {
      return null;
    }
    return libraryRepository
        .findById(document.getLibraryId())
        .map(library -> library.getName())
        .orElse(null);
  }

  private static ChunkInspection describe(
      StoredChunk chunk, Document document, String libraryName) {
    return new ChunkInspection(
        chunk.id(),
        document.getId(),
        document.getFileName(),
        document.getLibraryId(),
        libraryName,
        chunk.chunkIndex(),
        chunk.content(),
        chunk.metadata());
  }

  private StoredChunk toStoredChunk(java.sql.ResultSet rs, int rowNum)
      throws java.sql.SQLException {
    return new StoredChunk(
        rs.getString("id"), rs.getString("content"), readMetadata(rs.getString("metadata")));
  }

  private Map<String, Object> readMetadata(String metadataJson) {
    if (metadataJson == null || metadataJson.isBlank()) {
      return Map.of();
    }
    return objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
  }

  /** One raw vector-store row before it is resolved against the documents table. */
  private record StoredChunk(String id, String content, Map<String, Object> metadata) {

    Optional<UUID> documentId() {
      Object value = metadata.get(DOCUMENT_ID_KEY);
      if (value == null) {
        return Optional.empty();
      }
      try {
        return Optional.of(UUID.fromString(value.toString()));
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
    }

    Integer chunkIndex() {
      Object value = metadata.get(CHUNK_INDEX_KEY);
      if (value instanceof Number number) {
        return number.intValue();
      }
      if (value instanceof String text) {
        try {
          return Integer.valueOf(text);
        } catch (NumberFormatException e) {
          return null;
        }
      }
      return null;
    }
  }
}
