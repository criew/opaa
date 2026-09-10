package io.opaa.indexing.chunk;

import io.opaa.indexing.IndexingProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

public class ChunkingService {

  private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

  /**
   * Chunk metadata key carrying the human-readable Fundort - set by {@link
   * OverlappingTokenTextSplitter} from {@link ChunkLocationResolver}, copied onto the stored chunk
   * by {@code DocumentIngestService#storeChunks} and read back by {@code ChatSourceAssembler}.
   */
  public static final String LOCATION_METADATA_KEY = "location";

  /**
   * Chunk metadata key carrying the container a document came from - the same value as the
   * document's own {@code source_container_key} column. Source-neutral by name and by value; only
   * the Confluence storage format declares it as passthrough today, and only {@code
   * DocumentIngestService#attachSourceContext} sets it, because it is not in the body.
   */
  public static final String SOURCE_CONTAINER_METADATA_KEY = "source_container_key";

  /**
   * Chunk metadata key carrying the document's ancestors root first, joined with " / " - the same
   * value as the document's own column of that name. See {@link #SOURCE_CONTAINER_METADATA_KEY};
   * both keys always travel together.
   */
  public static final String SOURCE_HIERARCHY_METADATA_KEY = "source_hierarchy_path";

  private final IndexingProperties properties;

  public ChunkingService(IndexingProperties properties) {
    this.properties = properties;
  }

  public List<Document> chunkDocuments(String fileName, List<Document> documents) {
    log.info(
        "Splitting up document '{}' into chunks (chunkSize={}, chunkOverlap={})",
        fileName,
        properties.chunkSize(),
        properties.chunkOverlap());
    var tokenSplitter =
        TokenTextSplitter.builder()
            .withChunkSize(properties.chunkSize())
            // avoids tiny chunks that lack sufficient context for retrieval
            .withMinChunkSizeChars(350)
            // chunks under 5 tokens carry no meaningful semantic signal
            .withMinChunkLengthToEmbed(5)
            // safety limit to prevent excessive chunks from oversized documents
            .withMaxNumChunks(10000)
            .withKeepSeparator(true)
            .build();
    // Overlap is not a TokenTextSplitter feature in Spring AI 2.0.0 — see
    // OverlappingTokenTextSplitter for why it is needed and how it is applied.
    var splitter = new OverlappingTokenTextSplitter(tokenSplitter, properties.chunkOverlap());
    return splitter.apply(documents);
  }
}
