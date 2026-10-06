package io.opaa.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One citation marker {@code 【source: <documentId>#<chunk> | <fileName>】} as it literally appears
 * in an answer text. {@link #PATTERN} is the one definition of the marker syntax that the answer
 * pipeline and the transcript import share.
 *
 * @param chunkIndex {@code -1} when the digits do not fit an {@code int}
 */
public record CitationMarker(String documentId, int chunkIndex, String fileName) {

  public static final Pattern PATTERN =
      Pattern.compile("【source:\\s*([a-zA-Z0-9\\-]+)#(\\d+)\\s*\\|\\s*(.+?)】");

  /**
   * Everything that presents itself as a marker, well-formed or not - a superset of {@link
   * #PATTERN}. A model can get the syntax wrong; what matches here but not there is malformed.
   */
  public static final Pattern CLAIMED_PATTERN = Pattern.compile("【source:[^】]*】");

  /** The marker text in the syntax {@link #PATTERN} reads. */
  public static String render(String documentId, Object chunkIndex, String fileName) {
    return "【source: " + documentId + "#" + chunkIndex + " | " + fileName + "】";
  }

  /** Whether {@code marker}, one whole {@link #CLAIMED_PATTERN} match, is well-formed. */
  public static boolean isWellFormed(String marker) {
    return PATTERN.matcher(marker).matches();
  }

  /** Every marker of {@code text} in appearance order, duplicates included. */
  public static List<CitationMarker> parse(String text) {
    List<CitationMarker> markers = new ArrayList<>();
    if (text == null || text.isEmpty()) {
      return markers;
    }
    Matcher matcher = PATTERN.matcher(text);
    while (matcher.find()) {
      int chunkIndex;
      try {
        chunkIndex = Integer.parseInt(matcher.group(2).trim());
      } catch (NumberFormatException e) {
        chunkIndex = -1;
      }
      markers.add(new CitationMarker(matcher.group(1).trim(), chunkIndex, matcher.group(3).trim()));
    }
    return markers;
  }
}
