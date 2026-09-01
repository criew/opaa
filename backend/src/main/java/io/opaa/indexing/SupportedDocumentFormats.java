package io.opaa.indexing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
 * The single place that decides which documents this system accepts for indexing, and decides it
 * from the file's actual content rather than its name.
 *
 * <p>Both the filesystem path ({@link DocumentService}) and the network path ({@link
 * UrlIndexingExecutor}/{@link RssFeedIndexingExecutor}) ask this class the same question the same
 * way: what does {@link #detectMediaType} report for these bytes, not what does the file happen to
 * be named. {@link #decideForFileName} accepts a Tika-detected media type {@link #EXTENSIONS}
 * covers regardless of what the file is named; a claimed extension that does not match the detected
 * content is reported as a mismatch (see {@link ContentDecision#extensionMismatch()}), not silently
 * corrected or rejected.
 *
 * <p>{@code .doc} is part of the list because the extractor in use handles it: Tika's {@code
 * AutoDetectParser} on this classpath reports {@code application/msword} among its supported media
 * types (via {@code tika-parser-microsoft-module} and {@code poi-scratchpad}).
 *
 * <p>Upload path: {@link #contentMatchesExtension} still backs {@code
 * io.opaa.library.LibraryDocumentService#uploadDocument}'s own check, where a mismatch is rejected
 * outright rather than merely reported - a person uploading a file chose both the file and its name
 * in the same action, so a mismatch is more likely an honest mistake worth catching immediately
 * than an old archive's quirk. {@link #decideForFileName} reuses the same per-extension matching
 * {@link #contentMatchesExtension} exposes, just tolerating rather than rejecting a mismatch.
 */
public final class SupportedDocumentFormats {

  private static final Tika TIKA = new Tika();

  private static final Set<String> EXTENSIONS =
      Set.of(".md", ".txt", ".pdf", ".docx", ".doc", ".pptx", ".xlsx", ".csv");

  /**
   * Maps a {@code Content-Type} header value to one of the {@link #EXTENSIONS} above, for sources
   * that cannot expose a supported extension in the URL itself - the Government Site Builder
   * attachment profile ({@link AttachmentProfile#GSB}), whose addresses carry the file through a
   * query parameter instead of a path extension. Deliberately narrower than what Tika itself could
   * detect: this map only needs to cover the same formats {@link #EXTENSIONS} already accepts.
   */
  private static final Map<String, String> EXTENSIONS_BY_CONTENT_TYPE =
      Map.of(
          "application/pdf", ".pdf",
          "application/msword", ".doc",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx",
          "application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx",
          "text/plain", ".txt",
          "text/markdown", ".md",
          "text/csv", ".csv");

  /**
   * Extensions whose content is only checked for being text at all - {@code .md}, {@code .txt} and
   * {@code .csv} are barely distinguishable by content alone (a CSV file is valid Markdown and vice
   * versa), so demanding Tika detect one specific media type among them would produce false
   * positives on legitimate files far more often than it would catch a real mismatch. {@code .csv}
   * joins {@code .md}/{@code .txt} here for the same reason (ingestion-pipelines.md, Teil 3, Punkt
   * 3): content alone cannot tell a comma- or semicolon-separated export apart from a Markdown
   * table or plain text, so a CSV file is only accepted once its own extension already claims it.
   */
  private static final Set<String> TEXT_TOLERANT_EXTENSIONS = Set.of(".md", ".txt", ".csv");

  /**
   * The Tika-detected media type(s) consistent with each non-text extension in {@link #EXTENSIONS}.
   * Unlike {@link #TEXT_TOLERANT_EXTENSIONS}, these formats have a distinctive enough byte
   * signature (a PDF header, an OLE/ZIP container) that Tika's {@code AutoDetectParser} reliably
   * tells them apart from one another and from arbitrary binary content, so a mismatch here is
   * worth rejecting outright rather than tolerating.
   */
  private static final Map<String, Set<String>> STRICT_CONTENT_TYPES_BY_EXTENSION =
      Map.of(
          ".pdf", Set.of("application/pdf"),
          // Deliberately not including application/x-tika-msoffice: that is the generic, unresolved
          // OLE2 container type Tika falls back to when POI's format-specific sniffing inside the
          // container fails - any OLE2 file this system cannot actually identify would pass as a
          // "matching" .doc, defeating the point of this check.
          ".doc", Set.of("application/msword"),
          ".docx",
              Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
          ".pptx",
              Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
          ".xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

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
   * fall back to than what the URL already provided.
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
   * to be {@code extension}. {@code extension} must already be one of {@link #EXTENSIONS} ({@link
   * #isSupported} already returned {@code true} for the file name it was matched from); an
   * extension outside that set returns {@code false}, same as a {@code null} detection result.
   *
   * <p>{@link #TEXT_TOLERANT_EXTENSIONS} only demand the content look like text at all - not
   * literally {@code text/*} (that would reject, say, an XML-formatted .txt export), but anything
   * {@link MediaTypeRegistry#isInstanceOf} recognizes as {@code text/plain} or one of its declared
   * specializations in Tika's own media type hierarchy. {@code application/pdf} and the ZIP/OLE2-
   * based office types are not declared as such a specialization, so they are still rejected. Every
   * other supported extension demands one of the specific media types {@link
   * #STRICT_CONTENT_TYPES_BY_EXTENSION} lists for it.
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
   * Detects {@code file}'s media type from its actual bytes alone - no file name, extension or
   * declared {@code Content-Type} enters this call. Both indexing paths funnel through this one
   * method so neither can drift into using a filename-assisted detector by accident.
   */
  public static String detectMediaType(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      return TIKA.detect(in);
    }
  }

  /**
   * The number of leading bytes {@link #detectMediaType(byte[])} needs to reliably identify every
   * type {@link #EXTENSIONS} accepts - mirrors the 64 KiB default buffer Tika's own {@code
   * MimeTypes} magic detection reads from a stream ({@code MimeTypes#getMinLength()}).
   */
  public static final int DETECTION_PREFIX_BYTES = 65_536;

  /**
   * Detects a media type from a leading byte sample alone - the network path's own counterpart to
   * {@link #detectMediaType(Path)}, used before a file behind a listing is downloaded in full:
   * {@link UrlIndexingExecutor} reads at most {@link #DETECTION_PREFIX_BYTES} to decide whether an
   * entry is worth downloading at all, so an arbitrarily large file linked from a directory listing
   * never has to be written to disk in full only to be rejected afterwards.
   */
  public static String detectMediaType(byte[] contentPrefix) {
    try {
      return TIKA.detect(new ByteArrayInputStream(contentPrefix));
    } catch (IOException e) {
      // ByteArrayInputStream never actually throws - Tika#detect(InputStream) only declares the
      // checked exception because it accepts any InputStream.
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The extension in {@link #STRICT_CONTENT_TYPES_BY_EXTENSION} whose specific media type Tika's
   * {@code detectedMimeType} matches, or {@code null} when the content is not one of those - the
   * content-only counterpart to {@link #extensionForContentType}, which instead resolves a
   * declared, not detected, {@code Content-Type} header.
   *
   * <p>Deliberately excludes {@link #TEXT_TOLERANT_EXTENSIONS}: content alone cannot tell a
   * Markdown file apart from a CSV export, a log file or a piece of source code - treating any
   * plain-text content as an accepted "text document" regardless of what it is named would silently
   * widen this system's accepted Bestand. {@link #decideForFileName} is where the two are combined:
   * unambiguous content (this method) decides on its own; ambiguous, text-tolerant content only
   * counts once the file's own extension already says which of the text-tolerant types it claims to
   * be.
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
   * Whether {@code detectedMimeType} is Tika-detected PDF content - used by {@link
   * DocumentService#isTextlessPdf} to tell a scan PDF (ingestion-pipelines.md, Teil 3, Punkt 1
   * "Scan-Erkennung und Bestandsprüfung") apart from any other format that happens to also yield
   * blank extracted text.
   */
  public static boolean isPdfContent(String detectedMimeType) {
    return ".pdf".equals(extensionForDetectedContent(detectedMimeType));
  }

  /**
   * The entry of {@link #EXTENSIONS} that {@code fileName} ends with, or empty when it ends with
   * none of them - the file's own claimed extension, used only as a hint once {@link
   * #decideForFileName} has already decided acceptance from the content. Package-visible so {@code
   * io.opaa.library.LibraryDocumentService} can keep using its own equivalent private helper.
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
   * extension actually matches the detected content (a mismatch is reported, not silently corrected
   * or used to reject an otherwise-readable file).
   */
  public record ContentDecision(
      boolean supported, String detectedExtension, boolean extensionMismatch) {

    private static final ContentDecision UNSUPPORTED = new ContentDecision(false, null, false);
  }

  /**
   * The single decision both indexing paths make once a file's bytes are available.
   *
   * <p>An unambiguous, {@link #extensionForDetectedContent strictly detected} type is accepted
   * outright, regardless of what the file is named - {@code fileName}'s own claimed extension only
   * decides whether the caller needs to report a mismatch, never whether the file is indexed.
   *
   * <p>An ambiguous, text-tolerant detection (see {@link #TEXT_TOLERANT_EXTENSIONS}) is different:
   * content alone cannot tell a Markdown file apart from a CSV export or a source file, so this
   * only accepts it when {@code fileName}'s own extension already claims one of {@link
   * #TEXT_TOLERANT_EXTENSIONS} - never as a mismatch, and never for any other or missing extension.
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
