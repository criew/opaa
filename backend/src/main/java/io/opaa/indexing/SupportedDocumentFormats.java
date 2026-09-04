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
 * UrlIndexingExecutor}/{@code RssFeedIndexingExecutor}) ask this class the same question the same
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
   * Extensions whose content is only checked for being text at all - {@code .md}, {@code .txt} and
   * {@code .csv} are barely distinguishable by content alone (a CSV file is valid Markdown and vice
   * versa), so demanding Tika detect one specific media type among them would produce false
   * positives on legitimate files far more often than it would catch a real mismatch. {@code .csv}
   * joins {@code .md}/{@code .txt} here for the same reason (ingestion-pipelines.md, Teil 3, Punkt
   * 3): content alone cannot tell a comma- or semicolon-separated export apart from a Markdown
   * table or plain text, so a CSV file is only accepted once its own extension already claims it.
   *
   * <p>{@code .eml} joins this set (#1101 review) rather than the strict one below, for a reason
   * specific to Tika's own {@code message/rfc822} detector: it is a loose textual heuristic (looks
   * for header-shaped lines such as {@code Date:}/{@code Subject:}/{@code To:}/{@code From:} near
   * the top of the content), not a fixed byte signature the way a PDF header or an OLE2/ZIP
   * container is - {@code message/rfc822} is registered in Tika's own media type hierarchy as a
   * specialization of {@code text/plain} (confirmed empirically), so it is exactly as ambiguous by
   * content alone as Markdown or plain text: a log file with {@code Date:}/{@code Status:} lines, a
   * changelog with {@code To:}/{@code From:} lines, or a CSV export with {@code Date:}/{@code
   * Subject:} columns can trip the same heuristic. Treating {@code message/rfc822} as strictly
   * detected content (as an earlier version of this class did) would route such files into the mail
   * pipeline with no mismatch reported at all, and - the mirror failure - reject a genuine {@code
   * .eml} whose first header line does not match the heuristic (e.g. a leading {@code
   * Authentication-Results:} or a German {@code Von:}/{@code An:} pair) outright. Requiring the
   * file's own {@code .eml} extension in addition to "looks like text" fixes both: an unrelated
   * text file never gets routed as mail regardless of what its content resembles, and a genuine
   * {@code .eml} is admitted regardless of which header happens to come first.
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
   * The number of leading bytes {@link #detectMediaType(byte[])} needs to identify every type
   * {@link #EXTENSIONS} accepts by its own signature - mirrors the 64 KiB default buffer Tika's own
   * {@code MimeTypes} magic detection reads from a stream ({@code MimeTypes#getMinLength()}). Not
   * enough for a container whose identifying part may sit past the sample; {@link #decideForPrefix}
   * is where that case is resolved.
   */
  public static final int DETECTION_PREFIX_BYTES = 65_536;

  /**
   * Detects a media type from a leading byte sample alone - the network path's own counterpart to
   * {@link #detectMediaType(Path)}, used before a file behind a listing is downloaded in full:
   * {@code UrlIndexingExecutor} reads at most {@link #DETECTION_PREFIX_BYTES} to decide whether an
   * entry is worth downloading at all, so an arbitrarily large file linked from a directory listing
   * never has to be written to disk in full only to be rejected afterwards - except when the sample
   * yields an {@link #isUnresolvedContainerType unresolved container type}, which is no verdict at
   * all and makes {@link #decideForPrefix} fetch the complete file to decide.
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
   * inside it. A detection over a bounded prefix ({@link #DETECTION_PREFIX_BYTES}) runs into this
   * routinely: an OLE2 file's directory sector - the part naming the streams that identify a {@code
   * .msg} or {@code .doc} - can sit anywhere in the file, so any OLE2 document larger than the
   * sample detects as {@code application/x-tika-msoffice} there while its complete bytes detect as
   * the specific type. {@code application/x-tika-ooxml} is the same situation for a ZIP container.
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
   * The decision for a file whose bytes are, at first, only available as a leading prefix - the
   * network path's counterpart to calling {@link #decideForFileName} on a complete file.
   *
   * <p>The prefix decides on its own unless it detected one of {@link #UNRESOLVED_CONTAINER_TYPES}
   * and was not accepted: that outcome says the sample ended before the container revealed which
   * format it holds, not that the file is unsupported, so {@code completeContent} is fetched and
   * decides instead. Every other detection - a resolved type, or content Tika could not place at
   * all - is final on the prefix alone, so an entry this system does not want still costs a bounded
   * read rather than a full transfer.
   *
   * <p>The fallback does not resolve every container either: Tika's own {@code
   * POIFSContainerDetector} reads at most its {@code markLimit} (128 MiB by default) before
   * reporting the unresolved type again, so an OLE2 document larger than that stays rejected even
   * with its complete bytes at hand. On the network path the fetch itself is capped ({@code
   * CrawlProperties#maxFileSizeBytes}, #1236), by default below that limit - such an entry is then
   * rejected while streaming rather than after a full transfer; an installation raising the cap
   * past 128 MiB brings the case back.
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
   * Whether {@code detectedMimeType} is Tika-detected PDF content - used by {@code
   * io.opaa.indexing.pipeline.TikaFallbackPipeline#isTextlessPdf} to tell a scan PDF
   * (ingestion-pipelines.md, Teil 3, Punkt 1 "Scan-Erkennung und Bestandsprüfung") apart from any
   * other format that happens to also yield blank extracted text.
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
   * <p>The Markdown/Klartext/CSV special rule ({@link #TEXT_TOLERANT_EXTENSIONS}) is checked
   * <b>first</b>, ahead of any strict detection - not just as a fallback for content a strict
   * detection could not resolve at all. {@code text/html} is registered in Tika's own {@code
   * tika-mimetypes.xml} as a specialization of {@code text/plain} (confirmed empirically, see
   * {@code SupportedDocumentFormatsTest}), so a Markdown file that happens to open with a raw
   * {@code <div>}/{@code <h1>} detects as {@code text/html}, an otherwise-strict type (#1059
   * review, finding 1) - the special rule must still win, per ingestion-pipelines.md, Teil 1 ("gilt
   * für das Routing unverändert weiter"), or such a file would be silently routed to the HTML
   * pipeline with no {@code FORMAT_MISMATCH} even reported (the same content that makes the
   * text-tolerant match succeed also makes the strict branch's own mismatch check come out {@code
   * false}).
   *
   * <p>The same precedence is what makes {@code .eml} admission correct for a message whose HTML
   * body happens to be detected as {@code text/html} content (#1101 review): {@code text/html} is
   * {@code isInstanceOf text/plain} (see {@link #TEXT_TOLERANT_EXTENSIONS}'s own Javadoc on why
   * {@code .eml} joined this set), so such a file matches the text-tolerant branch on its own
   * {@code .eml} extension before the strict branch ever gets a say - the extension decides, not
   * the content, exactly as for the Markdown-detected-as-HTML case above. A file actually named
   * {@code .html} with the same content still takes the strict branch (its own extension is not
   * text-tolerant) and is routed to the HTML pipeline as normal; only a file already claiming
   * {@code .eml} benefits from this priority.
   *
   * <p>Once that is ruled out, an unambiguous, {@link #extensionForDetectedContent strictly
   * detected} type is accepted outright, regardless of what the file is named - {@code fileName}'s
   * own claimed extension only decides whether the caller needs to report a mismatch, never whether
   * the file is indexed. A text-tolerant name over genuinely non-text content (e.g. a PDF misnamed
   * {@code .csv}) never satisfies the first check above (PDF is not an {@code isInstanceOf
   * text/plain}), so it still falls through to this strict branch and is reported as a mismatch
   * there, exactly as before this method's own text-tolerant priority check existed.
   *
   * <p>Content that is neither a text-tolerant match nor a strict detection is unsupported -
   * content alone cannot tell a Markdown file apart from a CSV export or a source file, so an
   * ambiguous, text-tolerant detection is only ever accepted under one of {@link
   * #TEXT_TOLERANT_EXTENSIONS}, never as a mismatch, and never for any other or missing extension.
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
