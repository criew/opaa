package io.opaa.query.citation;

import io.opaa.chat.CitationMarker;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Deterministic extraction of the citation markers an answer carries. */
@Service
public class CitationParser {

  static final Pattern CITATION_PATTERN = CitationMarker.PATTERN;

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
    return CitationMarker.parse(answer).stream()
        .map(
            marker ->
                new ParsedCitation(marker.documentId(), marker.chunkIndex(), marker.fileName()))
        .collect(Collectors.toCollection(ArrayList::new));
  }
}
