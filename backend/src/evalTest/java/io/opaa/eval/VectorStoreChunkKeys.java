package io.opaa.eval;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link ExplanationDump.ChunkKeyResolver} over the harness's own {@code vector_store}: a chunk id
 * becomes {@code <file_name>#<chunk_index>}, a document id its file name. Read once after indexing,
 * so every dump of a run resolves against the same snapshot; an id the store does not know (the
 * {@code file:} fallback key, a synthetic test id) is kept unchanged and logged once, so a reader
 * of a non-empty diff can tell an unresolved key from a behaviour change.
 */
final class VectorStoreChunkKeys implements ExplanationDump.ChunkKeyResolver {

  private static final Logger log = LoggerFactory.getLogger(VectorStoreChunkKeys.class);

  private final Map<String, String> chunkKeysById;
  private final Map<String, String> fileNamesByDocumentId;
  private final Set<String> unresolvedChunkIds = new HashSet<>();

  private VectorStoreChunkKeys(
      Map<String, String> chunkKeysById, Map<String, String> fileNamesByDocumentId) {
    this.chunkKeysById = chunkKeysById;
    this.fileNamesByDocumentId = fileNamesByDocumentId;
  }

  static VectorStoreChunkKeys fromStore(JdbcTemplate jdbcTemplate) {
    Map<String, String> chunkKeysById = new HashMap<>();
    Map<String, String> fileNamesByDocumentId = new HashMap<>();
    jdbcTemplate.query(
        "SELECT id::text AS id, metadata->>'document_id' AS document_id,"
            + " metadata->>'file_name' AS file_name, metadata->>'chunk_index' AS chunk_index"
            + " FROM vector_store",
        rs -> {
          String fileName = rs.getString("file_name");
          chunkKeysById.put(rs.getString("id"), fileName + "#" + rs.getString("chunk_index"));
          String documentId = rs.getString("document_id");
          if (documentId != null) {
            fileNamesByDocumentId.put(documentId, fileName);
          }
        });
    return new VectorStoreChunkKeys(chunkKeysById, fileNamesByDocumentId);
  }

  @Override
  public String chunkKey(String chunkId) {
    String key = chunkKeysById.get(chunkId);
    if (key == null) {
      if (unresolvedChunkIds.add(chunkId)) {
        log.warn(
            "chunk id {} is not in vector_store - kept as is in the protocol dump ({} unresolved so"
                + " far)",
            chunkId,
            unresolvedChunkIds.size());
      }
      return chunkId;
    }
    return key;
  }

  @Override
  public String documentKey(String documentKey) {
    return fileNamesByDocumentId.getOrDefault(documentKey, documentKey);
  }
}
