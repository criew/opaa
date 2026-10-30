package io.opaa.format.chunk;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

/**
 * The generic token cut of the fallback format and the mail bodies. Before the text reaches the
 * tokenizer, a run without whitespace longer than {@link #MAX_RUN_CHARACTERS} gets a space
 * inserted, since the tokenizer holds memory linear in the length of one such run.
 */
public class ChunkingService {

  private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

  /** The longest run of non-whitespace characters handed to the tokenizer. */
  public static final int MAX_RUN_CHARACTERS = 1_000;

  /**
   * The most chunks one call may yield. The splitter is allowed one more, into which it puts the
   * whole rest of a longer text; {@link #exceedsChunkLimit} recognizes that result.
   */
  public static final int MAX_CHUNKS = 10_000;

  private final ChunkSizing properties;

  public ChunkingService(ChunkSizing properties) {
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
            // one past the limit, so a text needing more stays recognizable
            .withMaxNumChunks(MAX_CHUNKS + 1)
            .withKeepSeparator(true)
            .build();
    // Overlap is not a TokenTextSplitter feature in Spring AI 2.0.0 — see
    // OverlappingTokenTextSplitter for why it is needed and how it is applied.
    var splitter = new OverlappingTokenTextSplitter(tokenSplitter, properties.chunkOverlap());
    return splitter.apply(bounded(documents));
  }

  private List<Document> bounded(List<Document> documents) {
    List<Document> bounded = new ArrayList<>(documents.size());
    for (Document document : documents) {
      String text = document.getText() == null ? "" : document.getText();
      bounded.add(new Document(document.getId(), breakLongRuns(text), document.getMetadata()));
    }
    return bounded;
  }

  /**
   * Whether {@code chunks} from {@link #chunkDocuments} hold more than {@link #MAX_CHUNKS}: then
   * the last one carries the rest of the text uncut, and the result must not be used.
   */
  public static boolean exceedsChunkLimit(List<Document> chunks) {
    return chunks.size() > MAX_CHUNKS;
  }

  /**
   * {@code text} with a space after every {@link #MAX_RUN_CHARACTERS} characters that follow each
   * other without whitespace; never between the two halves of a surrogate pair.
   */
  static String breakLongRuns(String text) {
    StringBuilder result = null;
    int run = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c)) {
        run = 0;
      } else if (run >= MAX_RUN_CHARACTERS && !Character.isLowSurrogate(c)) {
        if (result == null) {
          result = new StringBuilder(text.length() + text.length() / MAX_RUN_CHARACTERS);
          result.append(text, 0, i);
        }
        result.append(' ');
        run = 0;
      }
      if (!Character.isWhitespace(c)) {
        run++;
      }
      if (result != null) {
        result.append(c);
      }
    }
    return result == null ? text : result.toString();
  }
}
