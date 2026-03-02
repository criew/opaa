package io.opaa.indexing;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

public class ChunkingService {

  private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

  private final IndexingProperties properties;

  public ChunkingService(IndexingProperties properties) {
    this.properties = properties;
  }

  public List<Document> chunkDocuments(String fileName, List<Document> documents) {
    log.info(
        "Splitting up document '{}' into chunks (chunkSize={})", fileName, properties.chunkSize());
    var splitter =
        new TokenTextSplitter(
            properties.chunkSize(),
            350, // minChunkSizeChars — avoids tiny chunks that lack sufficient context for
            // retrieval
            5, // minChunkLengthToEmbed — chunks under 5 tokens carry no meaningful semantic signal
            10000, // maxNumChunks — safety limit to prevent excessive chunks from oversized
            // documents
            true);
    return splitter.apply(documents);
  }
}
