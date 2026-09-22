package io.opaa.searchadmin;

import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.document.Document;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
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
 * Reads stored chunks straight out of the pgvector table - text and metadata only, the embedding
 * column is never selected. Two callers: the administration page (#1230), which lists a whole
 * document at once, and the reading path {@code io.opaa.search} (#1720), which reads a bounded
 * window or one page at a time and never materializes a whole document.
 *
 * <p>The organization boundary is checked against the {@code documents} table, never against the
 * chunk's own {@code organization_id} metadatum: older chunks may lack it, and a chunk whose
 * document no longer exists is treated as absent. Schema/table name come from the same {@code
 * spring.ai.vectorstore.pgvector.*} properties {@code PgVectorStore} binds, as in {@code
 * ChunkEmbeddingLookup}.
 *
 * <p>Organization membership is not a reading permission. The two administration entries ({@link
 * #inspectChunk}, {@link #inspectDocumentChunks}) therefore also require the caller to be able to
 * read the document's library, with no system-administration floor (#1828) - a chunk carries the
 * document's text. The reading path passes its own scope in and keeps deciding for itself.
 */
@Service
public class ChunkInspectionService {

  private static final String DOCUMENT_ID_KEY = VectorChunkStore.DOCUMENT_ID_METADATA_KEY;
  private static final String CHUNK_INDEX_KEY = "chunk_index";

  private final JdbcTemplate jdbcTemplate;
  private final DocumentRepository documentRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService libraryAccessService;
  private final ObjectMapper objectMapper;
  private final String selectSql;

  public ChunkInspectionService(
      JdbcTemplate jdbcTemplate,
      DocumentRepository documentRepository,
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService libraryAccessService,
      ObjectMapper objectMapper,
      @Value("${opaa.database.schema}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
    this.jdbcTemplate = jdbcTemplate;
    this.documentRepository = documentRepository;
    this.libraryRepository = libraryRepository;
    this.libraryAccessService = libraryAccessService;
    this.objectMapper = objectMapper;
    this.selectSql = "SELECT id, content, metadata FROM " + schemaName + "." + tableName;
  }

  /**
   * The chunk {@code chunkId} names, for the administration page - {@link #findChunk} plus the
   * reading permission on its library (#1828). Empty for an id the organization boundary already
   * hides; {@link io.opaa.common.AccessDeniedException} when the caller may administer that library
   * but not read it.
   */
  public Optional<ChunkInspection> inspectChunk(CurrentUser caller, String chunkId) {
    Optional<ChunkInspection> chunk = findChunk(caller.organizationId(), chunkId);
    chunk.ifPresent(found -> requireReadableLibrary(caller, found.libraryId()));
    return chunk;
  }

  /**
   * Every stored chunk of the document, for the administration page - {@link #listDocumentChunks}
   * plus the reading permission on its library (#1828), checked before a single chunk is read.
   */
  public DocumentChunks inspectDocumentChunks(CurrentUser caller, UUID documentId) {
    Document document = requireDocument(caller.organizationId(), documentId);
    requireReadableLibrary(caller, document.getLibraryId());
    return listDocumentChunks(caller.organizationId(), documentId);
  }

  /**
   * The reading permission a chunk's text needs: the same check the original's download runs, so
   * administering a library never opens its content. A document without a library, or one whose
   * library is gone, is treated as unknown.
   */
  private void requireReadableLibrary(CurrentUser caller, UUID libraryId) {
    KnowledgeLibrary library =
        (libraryId == null
                ? Optional.<KnowledgeLibrary>empty()
                : libraryRepository.findById(libraryId))
            .filter(candidate -> caller.organizationId().equals(candidate.getOrganizationId()))
            .orElseThrow(() -> new NotFoundException("Das Dokument wurde nicht gefunden."));
    libraryAccessService.requireContentRead(library, caller.id(), caller.isSystemAdmin());
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
    Document document = requireDocument(organizationId, documentId);
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

  /**
   * The chunks of {@code documentId} whose {@code chunk_index} lies within {@code [fromIndex,
   * toIndex]}, in index order. Bounded at the source: the caller that only needs a passage and its
   * neighbours must not pay for a document with tens of thousands of chunks (#1720). A chunk
   * without a {@code chunk_index} is not in any window.
   */
  public List<ChunkInspection> listChunkWindow(
      UUID organizationId, UUID documentId, int fromIndex, int toIndex) {
    Document document = requireDocument(organizationId, documentId);
    String libraryName = libraryName(document);
    return jdbcTemplate
        .query(
            selectSql
                + " WHERE metadata->>'"
                + DOCUMENT_ID_KEY
                + "' = ? AND (metadata->>'"
                + CHUNK_INDEX_KEY
                + "')::integer BETWEEN ? AND ? ORDER BY (metadata->>'"
                + CHUNK_INDEX_KEY
                + "')::integer",
            this::toStoredChunk,
            documentId.toString(),
            fromIndex,
            toIndex)
        .stream()
        .map(chunk -> describe(chunk, document, libraryName))
        .toList();
  }

  /**
   * At most {@code limit} chunks of {@code documentId} with a {@code chunk_index} greater than
   * {@code afterIndex}, in index order - the page a caller reading a whole document walks with, so
   * a character cap can stop the walk instead of trimming an already materialized text (#1720).
   * {@code afterIndex} of {@code null} starts at the first chunk.
   */
  public List<ChunkInspection> listChunkPage(
      UUID organizationId, UUID documentId, Integer afterIndex, int limit) {
    Document document = requireDocument(organizationId, documentId);
    String libraryName = libraryName(document);
    return jdbcTemplate
        .query(
            selectSql
                + " WHERE metadata->>'"
                + DOCUMENT_ID_KEY
                + "' = ? AND (metadata->>'"
                + CHUNK_INDEX_KEY
                + "')::integer > ? ORDER BY (metadata->>'"
                + CHUNK_INDEX_KEY
                + "')::integer LIMIT ?",
            this::toStoredChunk,
            documentId.toString(),
            afterIndex == null ? Integer.MIN_VALUE : afterIndex,
            limit)
        .stream()
        .map(chunk -> describe(chunk, document, libraryName))
        .toList();
  }

  /** The document, or {@link NotFoundException} for an unknown id and a foreign one alike. */
  private Document requireDocument(UUID organizationId, UUID documentId) {
    return documentRepository
        .findById(documentId)
        .filter(candidate -> organizationId.equals(candidate.getOrganizationId()))
        .orElseThrow(() -> new NotFoundException("Das Dokument wurde nicht gefunden."));
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
