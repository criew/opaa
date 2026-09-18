package io.opaa.mcp;

import io.opaa.search.SearchHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

  /**
   * How many passages the retrieval must deliver for {@code maxDocuments} summarised entries. The
   * wanted number is clamped <b>before</b> the multiplication: an overflowing product would turn
   * negative, and the search reads every value below one as "not requested" - a caller asking for a
   * huge number would then receive fewer passages than one asking for fifty.
   */
  int passagesFor(int maxDocuments) {
    return Math.max(1, Math.min(maxDocuments, Integer.MAX_VALUE / PASSAGES_PER_DOCUMENT))
        * PASSAGES_PER_DOCUMENT;
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
   * A window of {@link McpProperties#excerptCharacters()} around the <b>longest</b> term of the
   * question found in the passage, cut on word boundaries and marked with an ellipsis on every side
   * that was cut. The window starts a third before the match, so the sentence the term stands in is
   * visible with what follows it. A passage in which no term of the question appears - the ordinary
   * case for a purely vectorial hit - yields its beginning.
   */
  private String excerpt(String text, String query) {
    if (text == null || text.isBlank()) {
      return "";
    }
    int limit = properties.excerptCharacters();
    if (text.length() <= limit) {
      return text;
    }
    int match = foundPlace(text, query);
    int rawStart = Math.max(0, Math.min(match - limit / 3, text.length() - limit));
    int rawEnd = Math.min(text.length(), rawStart + limit);
    int start = rawStart == 0 ? 0 : wordStart(text, rawStart);
    int end = rawEnd == text.length() ? rawEnd : wordEnd(text, rawEnd);
    if (start >= end) {
      // A window without a word boundary - one unbroken token. Cut where the limit falls.
      start = rawStart;
      end = rawEnd;
    }
    start = wholeCharacter(text, start);
    end = wholeCharacter(text, end);
    return (start > 0 ? "…" : "")
        + text.substring(start, end).strip()
        + (end < text.length() ? "…" : "");
  }

  /**
   * Where the passage answers the question: the position of the <b>longest</b> term of the question
   * that occurs in it, or 0 when none does. The longest term carries the question - a German
   * question opens with a Fragewort of four letters or more ("welche", "gilt"), and picking the
   * earliest occurrence over all terms would put the window at the first such filler word instead
   * of at the subject term.
   *
   * <p>Compared case-insensitively <b>in the passage itself</b>, never in a lower-cased copy: the
   * lower case of some characters is longer than the original ({@code İ}), which would shift every
   * position after it.
   */
  private static int foundPlace(String text, String query) {
    if (query == null || query.isBlank()) {
      return 0;
    }
    String longest = null;
    for (String term : query.split("[^\\p{L}\\p{N}]+")) {
      if (term.length() < SIGNIFICANT_TERM_LENGTH) {
        continue;
      }
      if (longest == null || term.length() > longest.length()) {
        int found = indexOfIgnoringCase(text, term);
        if (found >= 0) {
          longest = term;
        }
      }
    }
    return longest == null ? 0 : indexOfIgnoringCase(text, longest);
  }

  private static int indexOfIgnoringCase(String text, String term) {
    for (int index = 0; index <= text.length() - term.length(); index++) {
      if (text.regionMatches(true, index, term, 0, term.length())) {
        return index;
      }
    }
    return -1;
  }

  /**
   * {@code position} moved off the second half of a surrogate pair. The word-boundary cuts land on
   * spaces, but the fallback for an unbroken token cuts by index - and half a pair in the answer is
   * a lone surrogate the JSON writer refuses, which would turn the tool call into a transport
   * error.
   */
  private static int wholeCharacter(String text, int position) {
    if (position > 0
        && position < text.length()
        && Character.isLowSurrogate(text.charAt(position))
        && Character.isHighSurrogate(text.charAt(position - 1))) {
      return position - 1;
    }
    return position;
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
