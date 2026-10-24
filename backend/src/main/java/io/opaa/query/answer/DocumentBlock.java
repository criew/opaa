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

  /** Chat-template role tokens such as {@code <|im_start|>} or {@code <|system|>}. */
  private static final Pattern ROLE_TOKEN = Pattern.compile("<\\|([^|<>\\s]{1,40})\\|>");

  /** Instruction-format markers such as {@code [INST]} and {@code <<SYS>>}. */
  private static final Pattern INSTRUCTION_MARKER =
      Pattern.compile("\\[\\s*/?\\s*INST\\s*]|<<\\s*/?\\s*SYS\\s*>>", Pattern.CASE_INSENSITIVE);

  /** A line that starts like the head line of a section. */
  private static final Pattern HEAD_LINE =
      Pattern.compile("(?im)^(\\s*)(Dokument|Zitiermarke)(\\s*):");

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
        cleanLine(chunk.getMetadata().getOrDefault("file_name", "unknown").toString());
    String documentId = cleanLine(chunk.getMetadata().getOrDefault("document_id", "").toString());
    String chunkIndex = cleanLine(chunk.getMetadata().getOrDefault("chunk_index", "0").toString());
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
    String cleaned = INVISIBLE.matcher(text).replaceAll("");
    String previous;
    do {
      previous = cleaned;
      cleaned = BLOCK.strip(SECTION.strip(cleaned));
    } while (!cleaned.equals(previous));
    cleaned = ROLE_TOKEN.matcher(cleaned).replaceAll("‹$1›");
    cleaned = INSTRUCTION_MARKER.matcher(cleaned).replaceAll("(Markierung)");
    cleaned = HEAD_LINE.matcher(cleaned).replaceAll("$1$2 (im Text)$3:");
    return cleaned.replace('【', '[').replace('】', ']');
  }

  /** {@link #clean} on one line: line breaks collapse into spaces. */
  static String cleanLine(String text) {
    return clean(text).replaceAll("[\\r\\n]+", " ").strip();
  }
}
