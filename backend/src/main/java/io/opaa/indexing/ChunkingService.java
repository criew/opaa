package io.opaa.indexing;

import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!mock")
public class ChunkingService {

  private final IndexingProperties properties;

  public ChunkingService(IndexingProperties properties) {
    this.properties = properties;
  }

  public List<Document> chunkDocuments(List<Document> documents) {
    var splitter =
        new TokenTextSplitter(
            properties.chunkSize(), /* defaultChunkSize */
            350, /* minChunkSizeChars */
            5, /* minChunkLengthToEmbed */
            10000, /* maxNumChunks */
            true /* keepSeparator */);
    return splitter.apply(documents);
  }
}
