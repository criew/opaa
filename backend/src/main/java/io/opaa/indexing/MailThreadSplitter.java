package io.opaa.indexing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits an email body into its individual messages when it carries a quoted reply chain
 * (docs/features/ingestion-pipelines.md, Teil 3, Punkt 5: "Ein Thread ist kein Dokument, sondern
 * eine Folge von Dokumenten").
 *
 * <p><b>Gesetzt, nicht gemessen</b> (ingestion-pipelines.md, "Chunk-Größen"): the three separator
 * patterns below cover the quoting conventions Outlook, Thunderbird and Gmail generate in German
 * and English plain-text replies - not every mail client's every locale. A body whose quoting style
 * this does not recognize is kept as a single message rather than mis-split; a false negative (an
 * unrecognized thread stays one chunk) is preferred over a false positive (ordinary body text
 * mis-cut mid-sentence on a line that happens to resemble a separator).
 */
final class MailThreadSplitter {

  /**
   * A line introducing a quoted prior message. Three families:
   *
   * <ul>
   *   <li>German Outlook/Thunderbird inline attribution: {@code "Am 03.01.2024 um 10:15 schrieb Max
   *       Mustermann <max@example.org>:"}
   *   <li>English Outlook/Gmail inline attribution: {@code "On Wed, Jan 3, 2024 at 10:15 AM John
   *       Doe <john@example.org> wrote:"}
   *   <li>Outlook's plain-text separator block, either language: a line of five or more hyphens
   *       around "Ursprüngliche Nachricht" / "Original Message"
   * </ul>
   *
   * Anchored to a whole line ({@code MULTILINE}, no {@code DOTALL}) so a quoted line that merely
   * mentions one of these phrases mid-sentence does not match.
   */
  private static final Pattern SEPARATOR =
      Pattern.compile(
          "^(Am .{1,120} schrieb .{1,200}:"
              + "|On .{1,150} wrote:"
              + "|-{5,}\\s*(Ursprüngliche Nachricht|Original Message)\\s*-{5,})$",
          Pattern.MULTILINE);

  private MailThreadSplitter() {}

  /**
   * @return {@code body} split at each recognized separator line, the separator itself kept as the
   *     start of the segment it introduces; a single-element list (the whole, untouched body) when
   *     no separator is found or {@code body} is blank
   */
  static List<String> split(String body) {
    if (body == null || body.isBlank()) {
      return List.of(body == null ? "" : body);
    }
    var matcher = SEPARATOR.matcher(body);
    List<Integer> starts = new ArrayList<>();
    while (matcher.find()) {
      starts.add(matcher.start());
    }
    if (starts.isEmpty()) {
      return List.of(body);
    }

    List<String> segments = new ArrayList<>();
    String first = body.substring(0, starts.get(0)).stripTrailing();
    if (!first.isBlank()) {
      segments.add(first);
    }
    for (int i = 0; i < starts.size(); i++) {
      int from = starts.get(i);
      int to = i + 1 < starts.size() ? starts.get(i + 1) : body.length();
      segments.add(body.substring(from, to).strip());
    }
    return segments;
  }
}
