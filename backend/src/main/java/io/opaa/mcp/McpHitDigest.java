package io.opaa.mcp;

import io.opaa.search.SearchHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The display layer of {@code search} over the hits of {@code io.opaa.search} (#1766): one entry
 * per document instead of one per passage, and an excerpt around the found place instead of the
 * whole passage.
 *
 * <p>It is a <b>presentation</b>, not a second retrieval: the order, the selection and the ranking
 * are those of {@link io.opaa.search.SearchService}, and {@code POST /api/v1/search} keeps
 * answering per passage with the full excerpt. The reason the two differ is the caller: a program
 * asks for what it wants, an assistant pays for every character out of its context window and
 * fetches the chosen passage in full anyway.
 *
 * <p>The first passage of a document is its best one - the hits arrive in rank order - and carries
 * the entry; the document's remaining passages become {@link Entry#further()}, so a model sees at a
 * glance that a document was found several times without paying for every passage.
 */
@Component
class McpHitDigest {

  /**
   * Passages requested per wanted document. Grouping can only summarise what it was given: without
   * this factor a document found five times would leave the answer with a single entry. Larger
   * values buy nothing - the retrieval's own selection is the ceiling.
   */
  static final int PASSAGES_PER_DOCUMENT = 3;

  /** The shortest term of a question that may point at the found place; below that it is noise. */
  private static final int SIGNIFICANT_TERM_LENGTH = 4;

  private final McpProperties properties;

  McpHitDigest(McpProperties properties) {
    this.properties = properties;
  }

  /**
   * One entry per document, in the order of the best passage of each, cut to {@code maxDocuments}.
   *
   * @param best the highest ranked passage of this document - the one an entry is shown with
   * @param excerpt {@code best}'s text around the found place, bounded by {@link
   *     McpProperties#excerptCharacters()}
   * @param further the document's remaining passages, in rank order, each fetchable by its own id
   */
  record Entry(SearchHit best, String excerpt, List<SearchHit> further) {}

  /** How many passages the retrieval must deliver for {@code maxDocuments} summarised entries. */
  int passagesFor(int maxDocuments) {
    return maxDocuments * PASSAGES_PER_DOCUMENT;
  }

  /** The number of documents a request without its own {@code maxHits} receives. */
  int defaultMaxHits() {
    return properties.defaultMaxHits();
  }

  List<Entry> condense(List<SearchHit> hits, String query, int maxDocuments) {
    Map<Object, List<SearchHit>> byDocument = new LinkedHashMap<>();
    for (SearchHit hit : hits) {
      // A hit whose document did not resolve stands for itself rather than joining a group of
      // unrelated passages.
      Object key = hit.documentId() != null ? hit.documentId() : hit.hitId();
      byDocument.computeIfAbsent(key, ignored -> new ArrayList<>()).add(hit);
    }
    List<Entry> entries = new ArrayList<>();
    for (List<SearchHit> passages : byDocument.values()) {
      if (entries.size() == maxDocuments) {
        break;
      }
      SearchHit best = passages.get(0);
      entries.add(
          new Entry(
              best,
              excerpt(best.excerpt(), query),
              List.copyOf(passages.subList(1, passages.size()))));
    }
    return List.copyOf(entries);
  }

  /**
   * A window of {@link McpProperties#excerptCharacters()} around the first significant term of the
   * question, cut on word boundaries and marked with an ellipsis on every side that was cut. The
   * window starts a third before the match, so the sentence the term stands in is visible with what
   * follows it. A passage in which no term of the question appears - the ordinary case for a purely
   * vectorial hit - yields its beginning.
   */
  private String excerpt(String text, String query) {
    if (text == null || text.isBlank()) {
      return "";
    }
    int limit = properties.excerptCharacters();
    if (text.length() <= limit) {
      return text;
    }
    int match = firstMatch(text, query);
    int rawStart = Math.max(0, Math.min(match - limit / 3, text.length() - limit));
    int rawEnd = Math.min(text.length(), rawStart + limit);
    int start = rawStart == 0 ? 0 : wordStart(text, rawStart);
    int end = rawEnd == text.length() ? rawEnd : wordEnd(text, rawEnd);
    if (start >= end) {
      // A window without a word boundary - one unbroken token. Cut where the limit falls.
      start = rawStart;
      end = rawEnd;
    }
    return (start > 0 ? "…" : "")
        + text.substring(start, end).strip()
        + (end < text.length() ? "…" : "");
  }

  /** The first position at which a significant term of the question occurs, or 0 for none. */
  private static int firstMatch(String text, String query) {
    if (query == null || query.isBlank()) {
      return 0;
    }
    String haystack = text.toLowerCase(Locale.ROOT);
    int earliest = -1;
    for (String term : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
      if (term.length() < SIGNIFICANT_TERM_LENGTH) {
        continue;
      }
      int found = haystack.indexOf(term);
      if (found >= 0 && (earliest < 0 || found < earliest)) {
        earliest = found;
      }
    }
    return Math.max(earliest, 0);
  }

  /** The next word boundary at or after {@code position}, so an excerpt never starts mid-word. */
  private static int wordStart(String text, int position) {
    int index = text.indexOf(' ', position);
    return index < 0 ? position : index + 1;
  }

  /** The last word boundary at or before {@code position}, so an excerpt never ends mid-word. */
  private static int wordEnd(String text, int position) {
    int index = text.lastIndexOf(' ', position);
    return index <= 0 ? position : index;
  }
}
