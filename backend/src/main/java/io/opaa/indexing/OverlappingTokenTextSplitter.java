package io.opaa.indexing;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import java.util.ArrayList;
import java.util.List;
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
 * just recall (issue #374).
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

  @Override
  protected List<String> splitText(String text) {
    List<String> chunks = delegateSplit(text);
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
