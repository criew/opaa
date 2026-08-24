package io.opaa.indexing;

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
   * by {@code FileProcessingService#storeChunks} and read back by {@code QueryService#mapSources}.
   */
  public static final String LOCATION_METADATA_KEY = "location";

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
