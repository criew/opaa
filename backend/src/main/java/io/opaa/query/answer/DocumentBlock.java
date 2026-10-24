package io.opaa.query.answer;

import io.opaa.common.ContentFence;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;

/**
 * The retrieved passages as marked data: one block whose markers carry an id fresh per request, one
 * section per passage with its head lines inside. Everything a document controls - text and file
 * name - is cleaned first, so no passage can close the block, open a section, forge a head line or
 * a citation marker, or smuggle a role token or an invisible character into the prompt.
 */
final class DocumentBlock {

  static final ContentFence BLOCK = ContentFence.of("opaadokumente");
  static final ContentFence SECTION = ContentFence.of("opaaabschnitt");

  /**
   * The marker of a passage as {@code CitationMarker} reads it; the brackets are reserved for it.
   */
  static final String CITATION_FORMAT = "【source: %s#%s | %s】";

  /** Control and format characters except tab, line break and the joiner inside emoji. */
  private static final Pattern INVISIBLE =
      Pattern.compile("[\\p{Cc}&&[^\\t\\n\\r]]|[\\p{Cf}&&[^\\u200D]]");

  /**
   * A joiner next to a letter: inside emoji it joins symbols, inside a word it only hides the word
   * from {@link #HEAD_LINE}.
   */
  private static final Pattern JOINER_IN_WORD =
      Pattern.compile("(?<=\\p{L})\\u200D|\\u200D(?=\\p{L})");

  /**
   * Chat-template role tokens between bars, ASCII or full-width: {@code <|im_start|>}, {@code
   * <|system|>}, {@code <｜User｜>}.
   */
  private static final Pattern ROLE_TOKEN =
      Pattern.compile("<[|\\uFF5C]([^|\\uFF5C<>\\s]{1,40})[|\\uFF5C]>");

  /** Turn and sequence tokens without bars: {@code <start_of_turn>}, {@code <s>}, {@code </s>}. */
  private static final Pattern TURN_TOKEN =
      Pattern.compile(
          "<\\s*(start_of_turn|end_of_turn|bos|eos|/?s)\\s*>", Pattern.CASE_INSENSITIVE);

  /** Instruction-format markers such as {@code [INST]} and {@code <<SYS>>}. */
  private static final Pattern INSTRUCTION_MARKER =
      Pattern.compile("\\[\\s*/?\\s*INST\\s*]|<<\\s*/?\\s*SYS\\s*>>", Pattern.CASE_INSENSITIVE);

  /** A line that starts like the head line of a section, with any space and either colon. */
  private static final Pattern HEAD_LINE =
      Pattern.compile("(?im)^([\\s\\p{Z}]*)(Dokument|Zitiermarke)([\\s\\p{Z}]*)([:\\uFF1A])");

  private DocumentBlock() {}

  /** A fresh id for the block of one request. */
  static String newId() {
    return ContentFence.newId();
  }

  /** The block over {@code chunks}, in their order; an empty list gives an empty block. */
  static String render(String id, List<Document> chunks) {
    String sections =
        chunks.stream().map(chunk -> section(id, chunk)).collect(Collectors.joining("\n\n"));
    return BLOCK.open(id) + "\n" + (sections.isEmpty() ? "" : sections + "\n") + BLOCK.close(id);
  }

  private static String section(String id, Document chunk) {
    String fileName =
        cleanName(chunk.getMetadata().getOrDefault("file_name", "unknown").toString());
    String documentId = cleanName(chunk.getMetadata().getOrDefault("document_id", "").toString());
    String chunkIndex = cleanName(chunk.getMetadata().getOrDefault("chunk_index", "0").toString());
    return SECTION.open(id)
        + "\nDokument: "
        + fileName
        + "\nZitiermarke: "
        + String.format(CITATION_FORMAT, documentId, chunkIndex, fileName)
        + "\n"
        + clean(chunk.getText() == null ? "" : chunk.getText())
        + "\n"
        + SECTION.close(id);
  }

  /**
   * Passage text as data: block and section markers removed, invisible characters removed, role
   * tokens and instruction markers defused, a head line turned into text, and the citation brackets
   * replaced so the text cannot carry a marker of its own.
   */
  static String clean(String text) {
    return HEAD_LINE.matcher(defuse(text)).replaceAll("$1$2 (im Text)$3$4");
  }

  /**
   * A name for a head line, on one line: {@link #clean} without the head-line rewrite, which cannot
   * apply after "Dokument: " and would only set the citation marker apart from the stored name.
   */
  static String cleanName(String text) {
    return defuse(text).replaceAll("[\\r\\n]+", " ").strip();
  }

  private static String defuse(String text) {
    String cleaned = INVISIBLE.matcher(text).replaceAll("");
    cleaned = JOINER_IN_WORD.matcher(cleaned).replaceAll("");
    String previous;
    do {
      previous = cleaned;
      cleaned = BLOCK.strip(SECTION.strip(cleaned));
    } while (!cleaned.equals(previous));
    cleaned = ROLE_TOKEN.matcher(cleaned).replaceAll("‹$1›");
    cleaned = TURN_TOKEN.matcher(cleaned).replaceAll("‹$1›");
    cleaned = INSTRUCTION_MARKER.matcher(cleaned).replaceAll("(Markierung)");
    return cleaned.replace('【', '[').replace('】', ']');
  }
}
