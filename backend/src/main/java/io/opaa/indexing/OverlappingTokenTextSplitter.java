package io.opaa.indexing;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

/**
 * A {@link TextSplitter} that adds a configurable overlap to Spring AI's {@link TokenTextSplitter}.
 *
 * <p>{@code TokenTextSplitter} in Spring AI 2.0.0 has no overlap option at all — it cuts the token
 * stream into disjoint windows. Without overlap a statement that straddles a chunk boundary is torn
 * apart: the heading or introducing clause ends up at the end of one chunk and the definition at
 * the start of the next, so neither half carries the claim on its own. That hurts citability, not
 * just recall.
 *
 * <p>This splitter therefore delegates the actual splitting and then prefixes every chunk after the
 * first with the last {@code overlapTokens} tokens of its predecessor. The overlap is measured in
 * tokens of the same {@link EncodingType#CL100K_BASE} encoding {@code TokenTextSplitter} uses for
 * {@code chunkSize}, so both numbers are directly comparable.
 */
class OverlappingTokenTextSplitter extends TextSplitter {

  private static final Encoding ENCODING =
      Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

  private final TokenTextSplitter delegate;
  private final int overlapTokens;

  OverlappingTokenTextSplitter(TokenTextSplitter delegate, int overlapTokens) {
    this.delegate = delegate;
    this.overlapTokens = overlapTokens;
  }

  /** Characters of a chunk's own (un-prefixed) text used to find it again in the source text. */
  private static final int PROBE_LENGTH = 80;

  /**
   * Splits like {@link TextSplitter#apply}, but additionally stamps every chunk with its {@link
   * ChunkingService#LOCATION_METADATA_KEY} - derived from where the chunk's own text (before the
   * overlap prefix is prepended) sits in the source document, so the location describes the chunk's
   * beginning, not the carried-over tail of its predecessor. Page-break markers (see {@link
   * PageMarkingContentHandler}) are stripped from the stored text here, after locating; they must
   * not reach the embedding. A chunk whose text cannot be found again in the source simply carries
   * no location.
   */
  @Override
  public List<Document> apply(List<Document> documents) {
    List<Document> result = new ArrayList<>();
    for (Document document : documents) {
      String text = document.getText() == null ? "" : document.getText();
      ChunkLocationResolver resolver = ChunkLocationResolver.forText(text);
      List<String> originals = delegateSplit(text);
      List<String> chunks = overlap(originals);
      int searchFrom = 0;
      for (int i = 0; i < chunks.size(); i++) {
        Map<String, Object> metadata = new HashMap<>(document.getMetadata());
        String original = originals.get(i);
        int start = locateChunk(text, original, searchFrom);
        if (start >= 0) {
          searchFrom = start + 1;
          String location = resolver.locate(start, start + original.length());
          if (location != null) {
            metadata.put(ChunkingService.LOCATION_METADATA_KEY, location);
          }
        }
        result.add(new Document(stripPageBreaks(chunks.get(i)), metadata));
      }
    }
    return result;
  }

  private static int locateChunk(String text, String chunk, int searchFrom) {
    String probe = chunk.length() > PROBE_LENGTH ? chunk.substring(0, PROBE_LENGTH) : chunk;
    if (probe.isBlank()) {
      return -1;
    }
    int found = text.indexOf(probe, searchFrom);
    return found >= 0 ? found : text.indexOf(probe);
  }

  private static String stripPageBreaks(String chunk) {
    return chunk.indexOf(ChunkLocationResolver.PAGE_BREAK) < 0
        ? chunk
        : chunk.replace(ChunkLocationResolver.PAGE_BREAK, '\n');
  }

  @Override
  protected List<String> splitText(String text) {
    return overlap(delegateSplit(text));
  }

  private List<String> overlap(List<String> chunks) {
    if (overlapTokens <= 0 || chunks.size() < 2) {
      return chunks;
    }

    List<String> overlapped = new ArrayList<>(chunks.size());
    overlapped.add(chunks.getFirst());
    for (int i = 1; i < chunks.size(); i++) {
      // Derived from the *original* predecessor, not from the already-prefixed one, so the overlap
      // window stays exactly overlapTokens wide instead of accumulating across the document.
      String carriedOver = trailingTokens(chunks.get(i - 1));
      overlapped.add(carriedOver.isEmpty() ? chunks.get(i) : carriedOver + " " + chunks.get(i));
    }
    return overlapped;
  }

  /**
   * {@code TokenTextSplitter#splitText} is {@code protected} and this class is not a subclass of
   * it, so the split is driven through the public {@link TextSplitter#split(Document)} entry point.
   * The throwaway {@link Document} carries no metadata — the real metadata is merged by the
   * surrounding {@link TextSplitter} once this method returns.
   */
  private List<String> delegateSplit(String text) {
    return delegate.split(new Document(text)).stream().map(Document::getText).toList();
  }

  private String trailingTokens(String previousChunk) {
    IntArrayList tokens = ENCODING.encode(previousChunk);
    int from = Math.max(0, tokens.size() - overlapTokens);
    var tail = new IntArrayList(tokens.size() - from);
    for (int i = from; i < tokens.size(); i++) {
      tail.add(tokens.get(i));
    }
    return ENCODING.decode(tail).trim();
  }
}
