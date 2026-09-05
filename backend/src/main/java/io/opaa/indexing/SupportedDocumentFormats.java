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
 * The single place that decides which documents this system accepts for indexing, from the file's
 * actual content rather than its name: the filesystem path and the network path both ask {@link
 * #decideForFileName} what {@link #detectMediaType} reports for these bytes. A claimed extension
 * that does not match is reported as a mismatch, never silently corrected or used to reject.
 *
 * <p>The upload path uses {@link #contentMatchesExtension} directly and rejects a mismatch
 * outright: whoever uploads chose file and name in one action.
 */
public final class SupportedDocumentFormats {

  private static final Tika TIKA = new Tika();

  private static final Set<String> EXTENSIONS =
      Set.of(
          ".md", ".txt", ".pdf", ".docx", ".doc", ".pptx", ".xlsx", ".csv", ".odt", ".ods", ".odp",
          ".html", ".eml", ".msg");

  /**
   * Maps a {@code Content-Type} header value to one of the {@link #EXTENSIONS} above, for sources
   * that cannot expose a supported extension in the URL itself - the Government Site Builder
   * attachment profile ({@code AttachmentProfile#GSB}), whose addresses carry the file through a
   * query parameter instead of a path extension. Deliberately narrower than what Tika itself could
   * detect: this map only needs to cover the same formats {@link #EXTENSIONS} already accepts.
   */
  private static final Map<String, String> EXTENSIONS_BY_CONTENT_TYPE =
      Map.ofEntries(
          Map.entry("application/pdf", ".pdf"),
          Map.entry("application/msword", ".doc"),
          Map.entry(
              "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
          Map.entry(
              "application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx"),
          Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
          Map.entry("text/plain", ".txt"),
          Map.entry("text/markdown", ".md"),
          Map.entry("text/csv", ".csv"),
          Map.entry("application/vnd.oasis.opendocument.text", ".odt"),
          Map.entry("application/vnd.oasis.opendocument.spreadsheet", ".ods"),
          Map.entry("application/vnd.oasis.opendocument.presentation", ".odp"),
          Map.entry("text/html", ".html"));

  /**
   * Extensions whose content is only checked for being text at all. {@code .md}, {@code .txt} and
   * {@code .csv} are barely distinguishable by content (a CSV file is valid Markdown), and Tika's
   * {@code message/rfc822} detector is a textual heuristic rather than a byte signature, so {@code
   * .eml} joins them: requiring the file's own extension in addition to "looks like text" keeps an
   * unrelated text file out of the mail pipeline and still admits a genuine {@code .eml}.
   */
  private static final Set<String> TEXT_TOLERANT_EXTENSIONS = Set.of(".md", ".txt", ".csv", ".eml");

  /**
   * The Tika-detected media type(s) consistent with each non-text extension in {@link #EXTENSIONS}.
   * Unlike {@link #TEXT_TOLERANT_EXTENSIONS}, these formats have a distinctive enough byte
   * signature (a PDF header, an OLE/ZIP container) that Tika's {@code AutoDetectParser} reliably
   * tells them apart from one another and from arbitrary binary content, so a mismatch here is
   * worth rejecting outright rather than tolerating.
   */
  private static final Map<String, Set<String>> STRICT_CONTENT_TYPES_BY_EXTENSION =
      Map.ofEntries(
          Map.entry(".pdf", Set.of("application/pdf")),
          // Deliberately not including application/x-tika-msoffice: that is the generic, unresolved
          // OLE2 container type Tika falls back to when POI's format-specific sniffing inside the
          // container fails - any OLE2 file this system cannot actually identify would pass as a
          // "matching" .doc, defeating the point of this check.
          Map.entry(".doc", Set.of("application/msword")),
          Map.entry(
              ".docx",
              Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
          Map.entry(
              ".pptx",
              Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation")),
          Map.entry(
              ".xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
          // ODF, unlike .docx/.pptx, has one specific, unambiguous media type per format straight
          // from Tika's ZIP-mimetype-entry detector - there is no generic "unresolved ODF
          // container" fallback the way there is application/x-tika-ooxml, so no exclusion note
          // is needed here.
          Map.entry(".odt", Set.of("application/vnd.oasis.opendocument.text")),
          Map.entry(".ods", Set.of("application/vnd.oasis.opendocument.spreadsheet")),
          Map.entry(".odp", Set.of("application/vnd.oasis.opendocument.presentation")),
          // HTML has a distinctive enough signature (DOCTYPE/<html> tag) that Tika reliably tells
          // it apart from plain text - both text/html and the XHTML variant are accepted, mirroring
          // DetailPageExtractor's own isHtmlContentType (ingestion-pipelines.md, Teil 3, Punkt 4).
          Map.entry(".html", Set.of("text/html", "application/xhtml+xml")),
          // MSG's application/vnd.ms-outlook is a genuinely distinctive OLE2/MAPI container type,
          // unlike EML's message/rfc822 (see TEXT_TOLERANT_EXTENSIONS's own Javadoc for why EML
          // joins the text-tolerant set instead).
          Map.entry(".msg", Set.of("application/vnd.ms-outlook")));

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
   * Whether Tika's detected {@code detectedMimeType} is consistent with a file claiming to be
   * {@code extension}; an extension outside {@link #EXTENSIONS} returns {@code false}, like a
   * {@code null} detection. {@link #TEXT_TOLERANT_EXTENSIONS} only demand anything {@link
   * MediaTypeRegistry#isInstanceOf} recognizes as {@code text/plain}, which PDF and the ZIP/OLE2
   * office types are not; every other extension demands a specific media type.
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
   * The number of leading bytes {@link #detectMediaType(byte[])} needs to identify every type
   * {@link #EXTENSIONS} accepts by its own signature - mirrors the 64 KiB default buffer Tika's own
   * {@code MimeTypes} magic detection reads from a stream ({@code MimeTypes#getMinLength()}). Not
   * enough for a container whose identifying part may sit past the sample; {@link #decideForPrefix}
   * is where that case is resolved.
   */
  public static final int DETECTION_PREFIX_BYTES = 65_536;

  /**
   * Detects a media type from a leading byte sample alone - the network path's counterpart to
   * {@link #detectMediaType(Path)}, so an arbitrarily large file behind a listing is never written
   * to disk in full only to be rejected. The exception is an {@link #isUnresolvedContainerType
   * unresolved container type}, which is no verdict and makes {@link #decideForPrefix} fetch the
   * complete file.
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
   * The generic container types Tika reports when it recognizes the container but not the format
   * inside it. A bounded prefix runs into this routinely: an OLE2 file's directory sector can sit
   * anywhere in the file, so any OLE2 document larger than the sample detects as {@code
   * application/x-tika-msoffice} there. {@code application/x-tika-ooxml} is the ZIP equivalent.
   */
  private static final Set<String> UNRESOLVED_CONTAINER_TYPES =
      Set.of("application/x-tika-msoffice", "application/x-tika-ooxml");

  /**
   * Whether {@code detectedMimeType} is one of {@link #UNRESOLVED_CONTAINER_TYPES} - a detection
   * that carries no verdict about the complete file. A caller holding only a prefix must therefore
   * not turn it into a rejection; {@link #decideForPrefix} is where that is enforced.
   */
  public static boolean isUnresolvedContainerType(String detectedMimeType) {
    if (detectedMimeType == null) {
      return false;
    }
    return UNRESOLVED_CONTAINER_TYPES.contains(
        detectedMimeType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT));
  }

  /** Supplies a file's complete content on demand, for {@link #decideForPrefix}. */
  @FunctionalInterface
  public interface CompleteContent {
    Path get() throws IOException, InterruptedException;
  }

  /**
   * The decision for a file whose bytes are, at first, only available as a leading prefix. The
   * prefix decides on its own unless it detected one of {@link #UNRESOLVED_CONTAINER_TYPES} without
   * being accepted - the sample then ended before the container revealed its format, so {@code
   * completeContent} is fetched and decides instead. Tika's {@code markLimit} leaves an OLE2
   * document past 128 MiB unresolved even then; the network path's size cap rejects it earlier.
   */
  public static ContentDecision decideForPrefix(
      String fileName, byte[] prefix, CompleteContent completeContent)
      throws IOException, InterruptedException {
    String detectedFromPrefix = detectMediaType(prefix);
    ContentDecision decision = decideForFileName(fileName, detectedFromPrefix);
    if (decision.supported() || !isUnresolvedContainerType(detectedFromPrefix)) {
      return decision;
    }
    return decideForFileName(fileName, detectMediaType(completeContent.get()));
  }

  /**
   * The extension in {@link #STRICT_CONTENT_TYPES_BY_EXTENSION} whose media type {@code
   * detectedMimeType} matches, or {@code null} otherwise - the content-only counterpart to {@link
   * #extensionForContentType}. Deliberately excludes {@link #TEXT_TOLERANT_EXTENSIONS}: accepting
   * any plain-text content as a "text document" regardless of its name would silently widen the
   * accepted Bestand. {@link #decideForFileName} combines the two.
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
   * extension actually matches the detected content (a mismatch is reported, never silently
   * corrected, and never a reason to reject an otherwise-readable file).
   */
  public record ContentDecision(
      boolean supported, String detectedExtension, boolean extensionMismatch) {

    private static final ContentDecision UNSUPPORTED = new ContentDecision(false, null, false);
  }

  /**
   * The single decision both indexing paths make once a file's bytes are available. {@link
   * #TEXT_TOLERANT_EXTENSIONS} is checked <b>first</b>, since {@code text/html} is a Tika
   * specialization of {@code text/plain} and a Markdown file opening with a raw {@code <div>} would
   * otherwise reach the HTML pipeline unreported. Otherwise a {@link #extensionForDetectedContent
   * strictly detected} type is accepted whatever the name; anything else is unsupported.
   */
  public static ContentDecision decideForFileName(String fileName, String detectedMimeType) {
    if (detectedMimeType == null) {
      return ContentDecision.UNSUPPORTED;
    }
    Optional<String> claimedExtension = matchedExtension(fileName);
    if (claimedExtension.isPresent()
        && TEXT_TOLERANT_EXTENSIONS.contains(claimedExtension.get())
        && contentMatchesExtension(claimedExtension.get(), detectedMimeType)) {
      return new ContentDecision(true, claimedExtension.get(), false);
    }
    String strictExtension = extensionForDetectedContent(detectedMimeType);
    if (strictExtension != null) {
      boolean matches =
          claimedExtension.isPresent()
              && contentMatchesExtension(claimedExtension.get(), detectedMimeType);
      return new ContentDecision(true, strictExtension, !matches);
    }
    return ContentDecision.UNSUPPORTED;
  }
}
