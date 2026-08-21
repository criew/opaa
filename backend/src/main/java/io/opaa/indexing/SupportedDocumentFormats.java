package io.opaa.indexing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.tika.Tika;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;

/**
 * The single place that decides which documents this system accepts for indexing (issue #375), and
 * - since #404 - the single place that decides it from the file's actual content rather than its
 * name.
 *
 * <p>Before #375, the filesystem path ({@link DocumentService}) and the network path ({@link
 * UrlIndexingExecutor}) each carried their own list, and the two had drifted apart: a legacy {@code
 * .doc} file was accepted when crawled from a web server and rejected when placed in the document
 * directory. Both paths now ask this class, and - since #404 - ask it the same question the same
 * way: what does {@link #detectMediaType} report for these bytes, not what does the file happen to
 * be named.
 *
 * <p>{@code .doc} is part of the list rather than being dropped, because the extractor in use
 * handles it: Tika's {@code AutoDetectParser} on this classpath reports {@code application/msword}
 * among its supported media types (via {@code tika-parser-microsoft-module} and {@code
 * poi-scratchpad}). Removing it would have thrown away working functionality to make two lists
 * agree.
 *
 * <p><strong>#404: content decides, the extension is a hint.</strong> {@link #decideForFileName} is
 * what both {@link DocumentService} (filesystem) and {@link UrlIndexingExecutor}/{@link
 * RssFeedIndexingExecutor} (network) call once a file's bytes are available - a Tika-detected media
 * type that {@link #EXTENSIONS} covers is accepted regardless of what the file is named; a claimed
 * extension that does not match the detected content is reported as a mismatch (see {@link
 * ContentDecision#extensionMismatch()}), not silently corrected or rejected. This closes the gap a
 * grown archive routinely has - a spreadsheet mislabeled {@code .txt} used to be indexed as garbled
 * text, and a document Tika can actually read used to be rejected outright for carrying the wrong
 * extension. Before this, the decision was made on the file extension alone; grown archives
 * routinely carry the wrong one, and the extractor in use (Tika's {@code AutoDetectParser}) already
 * reports 245 media types on this classpath - the list below remains a deliberate fachlich
 * boundary, not a technical one Tika could not exceed.
 *
 * <p><strong>Upload path (#435), superseded in spirit but kept as-is for its own reasons.</strong>
 * {@link #contentMatchesExtension} still backs {@code
 * io.opaa.library.LibraryDocumentService#uploadDocument}'s own check: the file name a person typed
 * during upload is itself part of what they asserted, so a mismatch there is rejected outright
 * rather than merely reported - a person uploading a file chose both the file and its name in the
 * same action, so a mismatch between what they typed and what the bytes actually are is far more
 * likely to be an honest mistake worth catching immediately than an old archive's quirk. {@link
 * #decideForFileName} reuses the very same per-extension matching {@link #contentMatchesExtension}
 * exposes, just tolerating rather than rejecting a mismatch.
 */
public final class SupportedDocumentFormats {

  private static final Tika TIKA = new Tika();

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

  /**
   * Detects {@code file}'s media type from its actual bytes alone (#404) - no file name, extension
   * or declared {@code Content-Type} enters this call, exactly like {@code
   * io.opaa.library.LibraryDocumentService}'s own {@code tika.detect(InputStream)} call the upload
   * path has used since #435 (per {@link Tika}'s own Javadoc: "based on the content of the given
   * document stream and does not use any hints such as the resource name"). Both indexing paths
   * funnel through this one method so neither can drift into using a filename-assisted detector by
   * accident.
   */
  public static String detectMediaType(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      return TIKA.detect(in);
    }
  }

  /**
   * The extension in {@link #STRICT_CONTENT_TYPES_BY_EXTENSION} whose specific media type Tika's
   * {@code detectedMimeType} matches, or {@code null} when the content is not one of those (#404) -
   * the content-only counterpart to {@link #extensionForContentType}, which instead resolves a
   * declared, not detected, {@code Content-Type} header. Reuses {@link #contentMatchesExtension}'s
   * own per-extension rules so the two methods can never silently drift apart on what "matches"
   * means for a given extension.
   *
   * <p><b>Deliberately excludes {@link #TEXT_TOLERANT_EXTENSIONS} (#404).</b> Content alone cannot
   * tell a Markdown file apart from a CSV export, a log file or a piece of source code (see that
   * field's Javadoc) - treating any plain-text content as an accepted "text document" regardless of
   * what it is named would silently widen this system's accepted Bestand past what #404 decided to
   * keep unchanged. {@link #decideForFileName} is where the two are combined: unambiguous content
   * (this method) decides on its own; ambiguous, text-tolerant content only counts once the file's
   * own extension already says which of the text-tolerant types it claims to be.
   */
  public static String extensionForDetectedContent(String detectedMimeType) {
    if (detectedMimeType == null) {
      return null;
    }
    for (String extension : STRICT_CONTENT_TYPES_BY_EXTENSION.keySet()) {
      if (contentMatchesExtension(extension, detectedMimeType)) {
        return extension;
      }
    }
    return null;
  }

  /**
   * The entry of {@link #EXTENSIONS} that {@code fileName} ends with, or empty when it ends with
   * none of them - the file's own claimed extension, used only as a hint (#404) once {@link
   * #decideForFileName} has already decided acceptance from the content. Package-visible so {@code
   * io.opaa.library.LibraryDocumentService} can keep using its own equivalent private helper
   * unchanged; both implement the same rule.
   */
  static Optional<String> matchedExtension(String fileName) {
    if (fileName == null || fileName.isBlank()) {
      return Optional.empty();
    }
    String lowerCased = fileName.toLowerCase(Locale.ROOT);
    return EXTENSIONS.stream().filter(lowerCased::endsWith).findFirst();
  }

  /**
   * Whether a document is accepted for indexing and, if so, whether {@code fileName}'s own claimed
   * extension actually matches the detected content (#404 acceptance criteria: a mismatch is
   * reported, not silently corrected or used to reject an otherwise-readable file).
   */
  public record ContentDecision(
      boolean supported, String detectedExtension, boolean extensionMismatch) {

    private static final ContentDecision UNSUPPORTED = new ContentDecision(false, null, false);
  }

  /**
   * The single decision both indexing paths make once a file's bytes are available (#404).
   *
   * <p>An unambiguous, {@link #extensionForDetectedContent strictly detected} type is accepted
   * outright, regardless of what the file is named - {@code fileName}'s own claimed extension only
   * decides whether the caller needs to report a mismatch, never whether the file is indexed.
   *
   * <p>An ambiguous, text-tolerant detection (see {@link #TEXT_TOLERANT_EXTENSIONS}) is a different
   * case (#404: "die Endung geht allenfalls als Hinweis ein - z. B. bei ambigen Typen"): content
   * alone cannot tell a Markdown file apart from a CSV export or a source file, so this only
   * accepts it when {@code fileName}'s own extension already claims one of {@link
   * #TEXT_TOLERANT_EXTENSIONS} - never as a mismatch (the extension confirmed the very type being
   * decided), and never for any other or missing extension. A plain-text file named anything else
   * is not one of the six accepted types this system decided to keep (#404 acceptance criteria: no
   * widening of the Bestand), even though Tika can read it as text just fine.
   */
  public static ContentDecision decideForFileName(String fileName, String detectedMimeType) {
    if (detectedMimeType == null) {
      return ContentDecision.UNSUPPORTED;
    }
    String strictExtension = extensionForDetectedContent(detectedMimeType);
    if (strictExtension != null) {
      Optional<String> claimedExtension = matchedExtension(fileName);
      boolean matches =
          claimedExtension.isPresent()
              && contentMatchesExtension(claimedExtension.get(), detectedMimeType);
      return new ContentDecision(true, strictExtension, !matches);
    }
    Optional<String> claimedExtension = matchedExtension(fileName);
    if (claimedExtension.isPresent()
        && TEXT_TOLERANT_EXTENSIONS.contains(claimedExtension.get())
        && contentMatchesExtension(claimedExtension.get(), detectedMimeType)) {
      return new ContentDecision(true, claimedExtension.get(), false);
    }
    return ContentDecision.UNSUPPORTED;
  }
}
