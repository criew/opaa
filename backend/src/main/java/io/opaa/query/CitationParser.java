package io.opaa.query;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Deterministic extraction of the citation markers an answer carries. */
@Service
public class CitationParser {

  static final Pattern CITATION_PATTERN =
      Pattern.compile("【source:\\s*([a-zA-Z0-9\\-]+)#(\\d+)\\s*\\|\\s*(.+?)】");

  /**
   * One citation marker as it literally appears in the answer text - the input to {@link
   * CitationValidator}. {@code chunkIndex} is {@code -1} when the digits the marker carries do not
   * fit an {@code int}; that value matches no real chunk, so it validates to "invalid" rather than
   * throwing.
   */
  public record ParsedCitation(String documentId, int chunkIndex, String fileName) {}

  /**
   * Extracts every citation marker in appearance order, duplicates included: two markers can share
   * a document id while differing in section number or claimed file name, and each is validated
   * independently against the chunks actually retrieved for this answer.
   */
  public List<ParsedCitation> extractCitations(String answer) {
    List<ParsedCitation> citations = new ArrayList<>();
    if (answer == null || answer.isEmpty()) {
      return citations;
    }
    Matcher matcher = CITATION_PATTERN.matcher(answer);
    while (matcher.find()) {
      String documentId = matcher.group(1).trim();
      String fileName = matcher.group(3).trim();
      int chunkIndex;
      try {
        chunkIndex = Integer.parseInt(matcher.group(2).trim());
      } catch (NumberFormatException e) {
        chunkIndex = -1;
      }
      citations.add(new ParsedCitation(documentId, chunkIndex, fileName));
    }
    return citations;
  }
}
