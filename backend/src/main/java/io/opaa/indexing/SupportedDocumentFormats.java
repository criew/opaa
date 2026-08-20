package io.opaa.indexing;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;

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
 *
 * <p><strong>Exception, scoped to the user-controlled upload path only (#435):</strong> {@link
 * #contentMatchesExtension} adds a second, content-based check on top of the extension decision
 * above, for {@code io.opaa.library.LibraryDocumentService#uploadDocument} alone. Operator-managed
 * sources (filesystem discovery, network crawling) keep the extension-only decision this class
 * Javadoc otherwise describes - the reasoning in #404 (a wrong extension in a grown, operator-owned
 * archive is usually a labeling mistake worth indexing anyway) still applies there. A person
 * uploading a file through the UI is a different situation: they chose both the file and its name
 * in the same action, so a mismatch between what they typed and what the bytes actually are is far
 * more likely to be an honest mistake worth catching immediately than an old archive's quirk.
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

  /**
   * Extensions whose content is only checked for being text at all (#435, Maintainer-Entscheidung
   * 20.08.2026, "Toleranz bei Textformaten") - {@code .md}, {@code .txt} and {@code .csv} are
   * barely distinguishable by content alone (a CSV file is valid Markdown and vice versa), so
   * demanding Tika detect one specific media type among them would produce false positives on
   * legitimate files far more often than it would catch a real mismatch. Only {@code .md} and
   * {@code .txt} are listed here today because those are the only text extensions {@link
   * #EXTENSIONS} currently accepts; {@code .csv} is not itself a supported upload format yet.
   */
  private static final Set<String> TEXT_TOLERANT_EXTENSIONS = Set.of(".md", ".txt");

  /**
   * The Tika-detected media type(s) consistent with each non-text extension in {@link #EXTENSIONS}
   * (#435). Unlike {@link #TEXT_TOLERANT_EXTENSIONS}, these formats have a distinctive enough byte
   * signature (a PDF header, an OLE/ZIP container) that Tika's {@code AutoDetectParser} reliably
   * tells them apart from one another and from arbitrary binary content, so a mismatch here is
   * worth rejecting outright rather than tolerating.
   */
  private static final Map<String, Set<String>> STRICT_CONTENT_TYPES_BY_EXTENSION =
      Map.of(
          ".pdf", Set.of("application/pdf"),
          // Deliberately *not* including application/x-tika-msoffice (#435 code review): that is
          // the generic, unresolved OLE2 container type Tika falls back to when POI's format-
          // specific sniffing inside the container fails - any OLE2 file this system cannot
          // actually identify would pass as a "matching" .doc, defeating the point of this check.
          // A real .doc Tika can parse is reported as application/msword (see the class Javadoc's
          // note on tika-parser-microsoft-module/poi-scratchpad); anything that only resolves to
          // the generic container type is content this system could not positively identify as
          // Word at all, and is rejected like any other mismatch.
          ".doc", Set.of("application/msword"),
          ".docx",
              Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
          ".pptx",
              Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation"));

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

  /**
   * Whether Tika's magic-byte-detected {@code detectedMimeType} is consistent with a file claiming
   * to be {@code extension} - the upload-path-only check #435 adds (see the class Javadoc). {@code
   * extension} must already be one of {@link #EXTENSIONS} (i.e. {@link #isSupported} already
   * returned {@code true} for the file name it was matched from); an extension outside that set has
   * no entry in either map below and this method returns {@code false} for it, same as for a {@code
   * null} detection result.
   *
   * <p>{@link #TEXT_TOLERANT_EXTENSIONS} only demand the content look like text at all - not
   * literally {@code text/*} (#435 code review: that would reject, say, an XML-formatted .txt
   * export, since Tika reports XML as {@code application/xml}), but anything {@link
   * MediaTypeRegistry#isInstanceOf} recognizes as {@code text/plain} or one of its declared
   * specializations in Tika's own media type hierarchy - {@code application/xml}, {@code
   * application/rtf} and {@code message/rfc822} among them, per {@code tika-mimetypes.xml}'s {@code
   * sub-class-of} declarations. {@code application/pdf} and the ZIP/OLE2-based office types are not
   * declared as such a specialization, so they are still rejected. Every other supported extension
   * demands one of the specific media types {@link #STRICT_CONTENT_TYPES_BY_EXTENSION} lists for
   * it.
   */
  public static boolean contentMatchesExtension(String extension, String detectedMimeType) {
    if (detectedMimeType == null) {
      return false;
    }
    String normalized = detectedMimeType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    if (TEXT_TOLERANT_EXTENSIONS.contains(extension)) {
      MediaTypeRegistry registry = MediaTypeRegistry.getDefaultRegistry();
      MediaType detected = registry.normalize(MediaType.parse(normalized));
      return detected != null && registry.isInstanceOf(detected, MediaType.TEXT_PLAIN);
    }
    Set<String> expected = STRICT_CONTENT_TYPES_BY_EXTENSION.get(extension);
    return expected != null && expected.contains(normalized);
  }
}
