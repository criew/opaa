package io.opaa.retrieval.search;

import java.util.List;
import org.springframework.ai.document.Document;

/**
 * One full-text query's answer: the chunks, best first, and the uppercase words of the question
 * that were searched as ordinary words instead of as weight-{@code A} identifiers, because more
 * chunks of the search scope carry them than the list holds. The words are named as the question
 * wrote them; how many chunks carry them is deliberately not part of the result.
 */
public record FullTextSearchResult(List<Document> chunks, List<String> demotedIdentifiers) {

  public FullTextSearchResult {
    chunks = List.copyOf(chunks);
    demotedIdentifiers = List.copyOf(demotedIdentifiers);
  }

  static FullTextSearchResult empty() {
    return new FullTextSearchResult(List.of(), List.of());
  }
}
