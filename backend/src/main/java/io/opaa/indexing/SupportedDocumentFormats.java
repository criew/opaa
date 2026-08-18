package io.opaa.indexing;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The single place that decides which documents this system accepts for indexing (issue #375).
 *
 * <p>Before this class existed, the filesystem path ({@link DocumentService}) and the network path
 * ({@link UrlIndexingExecutor}) each carried their own list, and the two had drifted apart: a
 * legacy {@code .doc} file was accepted when crawled from a web server and rejected when placed in
 * the document directory. The same document being taken or refused depending on how it arrived is
 * not predictable for anyone and not explainable to whoever runs the installation. Both paths now
 * ask this class.
 *
 * <p>{@code .doc} is part of the list rather than being dropped from the network path, because the
 * extractor in use handles it: Tika's {@code AutoDetectParser} on this classpath reports {@code
 * application/msword} among its supported media types (via {@code tika-parser-microsoft-module} and
 * {@code poi-scratchpad}). Removing it would have thrown away working functionality to make two
 * lists agree.
 *
 * <p><strong>The decision is made on the file extension, not on the detected content.</strong> In
 * grown archives files routinely carry the wrong extension, and a spreadsheet treated as plain text
 * produces chunks that look like columns of numbers without context. Switching to content detection
 * touches the per-type extraction strategy and is therefore tracked separately — see issue #404.
 */
public final class SupportedDocumentFormats {

  private static final Set<String> EXTENSIONS =
      Set.of(".md", ".txt", ".pdf", ".docx", ".doc", ".pptx");

  /**
   * Maps a {@code Content-Type} header value to one of the {@link #EXTENSIONS} above, for sources
   * that cannot expose a supported extension in the URL itself - the Government Site Builder
   * attachment profile (#468, {@link AttachmentProfile#GSB}), whose addresses carry the file
   * through a query parameter instead of a path extension. Deliberately narrower than what Tika
   * itself could detect: this map only needs to cover the same formats {@link #EXTENSIONS} already
   * accepts, not content detection in general (see the class Javadoc's note on issue #404).
   */
  private static final Map<String, String> EXTENSIONS_BY_CONTENT_TYPE =
      Map.of(
          "application/pdf", ".pdf",
          "application/msword", ".doc",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx",
          "application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx",
          "text/plain", ".txt",
          "text/markdown", ".md");

  private SupportedDocumentFormats() {}

  /** Whether a document with this file name is accepted for indexing, on either path. */
  public static boolean isSupported(String fileName) {
    if (fileName == null || fileName.isBlank()) {
      return false;
    }
    String lowerCased = fileName.toLowerCase();
    return EXTENSIONS.stream().anyMatch(lowerCased::endsWith);
  }

  /** The accepted extensions, sorted, for log and error messages. */
  public static List<String> extensions() {
    return EXTENSIONS.stream().sorted().toList();
  }

  /**
   * The extension {@code EXTENSIONS_BY_CONTENT_TYPE} associates with {@code contentType}, or {@code
   * null} when the content type is absent or unrecognized - the caller then has no better name to
   * fall back to than what the URL already provided (#468).
   */
  public static String extensionForContentType(String contentType) {
    if (contentType == null) {
      return null;
    }
    String mediaType = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    return EXTENSIONS_BY_CONTENT_TYPE.get(mediaType);
  }
}
