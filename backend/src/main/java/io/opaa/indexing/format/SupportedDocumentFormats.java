package io.opaa.indexing.format;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.apache.tika.Tika;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;

/**
 * The single place that decides which documents this system accepts for indexing, from the file's
 * actual content rather than its name: the filesystem path and the network path both ask {@link
 * #decideForFileName} what {@link #detectMediaType} reports for these bytes. A claimed extension
 * that does not match is reported as a mismatch, never silently corrected or used to reject.
 *
 * <p>The accepted set is not listed here: it is the union of every registered {@link
 * DocumentFormat}'s {@link DocumentFormat#admittedFormats()}, read once at construction. Adding a
 * format therefore never changes this class.
 *
 * <p>The upload path uses {@link #contentMatchesExtension} directly and rejects a mismatch
 * outright: whoever uploads chose file and name in one action.
 */
public final class SupportedDocumentFormats {

  private static final Tika TIKA = new Tika();

  private final Set<String> extensions;
  private final List<String> sortedExtensions;
  private final Map<String, String> canonicalMediaTypeByExtension;
  private final Map<String, Set<String>> detectedMediaTypesByExtension;
  private final Map<String, String> extensionByDetectedMediaType;
  private final Map<String, String> extensionByDeclaredContentType;
  private final Set<String> textTolerantExtensions;

  /**
   * Derives the accepted set from {@code formats} - every registered {@link DocumentFormat},
   * including the fallback, whose own declaration is what keeps an admitted extension without a
   * specialized format ({@code .txt}, {@code .doc}) a named decision rather than a leftover.
   *
   * @throws IllegalStateException two formats declare the same extension or the same media type, or
   *     one format names a media type under two of its extensions; bean order would otherwise
   *     silently decide which of them a document reaches
   */
  public SupportedDocumentFormats(Collection<? extends DocumentFormat> formats) {
    Map<String, String> canonicalByExtension = new HashMap<>();
    Map<String, Set<String>> detectedByExtension = new HashMap<>();
    Map<String, String> byDetectedMediaType = new HashMap<>();
    Map<String, String> byDeclaredContentType = new HashMap<>();
    Map<String, String> declaringFormatByExtension = new HashMap<>();
    Map<String, String> declaringFormatByMediaType = new HashMap<>();
    Map<String, String> namingExtensionByMediaType = new HashMap<>();
    Set<String> textTolerant = new HashSet<>();
    for (DocumentFormat format : formats) {
      for (FormatAdmission admission : format.admittedFormats()) {
        String extension = admission.extension();
        String previousFormat = declaringFormatByExtension.put(extension, format.id());
        if (previousFormat != null) {
          throw new IllegalStateException(
              previousFormat.equals(format.id())
                  ? "Document format " + format.id() + " admits " + extension + " twice"
                  : "Two document formats admit "
                      + extension
                      + ": "
                      + previousFormat
                      + " and "
                      + format.id());
        }
        canonicalByExtension.put(extension, admission.canonicalMediaType());
        detectedByExtension.put(extension, admission.detectedMediaTypes());
        if (admission.textTolerant()) {
          textTolerant.add(extension);
        }
        for (String mediaType : mediaTypesOf(admission)) {
          // A format may admit one media type under several extensions (".htm" beside ".html");
          // two formats may not, since nothing but iteration order could then decide between them.
          String previousClaim = declaringFormatByMediaType.put(mediaType, format.id());
          if (previousClaim != null && !previousClaim.equals(format.id())) {
            throw new IllegalStateException(
                "Two document formats admit the media type "
                    + mediaType
                    + ": "
                    + previousClaim
                    + " and "
                    + format.id());
          }
          if (!admission.namesItsMediaTypes()) {
            continue;
          }
          String previousName = namingExtensionByMediaType.put(mediaType, extension);
          if (previousName != null) {
            throw new IllegalStateException(
                "Document format "
                    + format.id()
                    + " names the media type "
                    + mediaType
                    + " under two extensions, "
                    + previousName
                    + " and "
                    + extension
                    + " - one of them is an alternate spelling");
          }
          if (admission.detectedMediaTypes().contains(mediaType)) {
            byDetectedMediaType.put(mediaType, extension);
          }
          if (admission.namedByDeclaredContentType()
              && mediaType.equals(admission.canonicalMediaType())) {
            byDeclaredContentType.put(mediaType, extension);
          }
        }
      }
    }
    this.extensions = Set.copyOf(canonicalByExtension.keySet());
    this.sortedExtensions = List.copyOf(new TreeSet<>(this.extensions));
    this.canonicalMediaTypeByExtension = Map.copyOf(canonicalByExtension);
    this.detectedMediaTypesByExtension = Map.copyOf(detectedByExtension);
    this.extensionByDetectedMediaType = Map.copyOf(byDetectedMediaType);
    this.extensionByDeclaredContentType = Map.copyOf(byDeclaredContentType);
    this.textTolerantExtensions = Set.copyOf(textTolerant);
  }

  /** Every media type an admission speaks for - its canonical one and its detections. */
  private static Set<String> mediaTypesOf(FormatAdmission admission) {
    Set<String> mediaTypes = new HashSet<>(admission.detectedMediaTypes());
    mediaTypes.add(admission.canonicalMediaType());
    return mediaTypes;
  }

  /** Whether a document with this file name is accepted for indexing, on either path. */
  public boolean isSupported(String fileName) {
    if (fileName == null || fileName.isBlank()) {
      return false;
    }
    String lowerCased = fileName.toLowerCase(Locale.ROOT);
    return extensions.stream().anyMatch(lowerCased::endsWith);
  }

  /** The accepted extensions, sorted, for log and error messages. */
  public List<String> extensions() {
    return sortedExtensions;
  }

  /**
   * The extension a declared {@code Content-Type} header names, or {@code null} when the content
   * type is absent, unrecognized or declared as {@link
   * FormatAdmission#notNamedByDeclaredContentType() not named by a header} - the caller then has no
   * better name to fall back to than what the URL already provided. For sources that cannot expose
   * a supported extension in the URL itself: the Government Site Builder attachment profile ({@code
   * AttachmentProfile#GSB}), whose addresses carry the file through a query parameter, and an S3
   * key without one. Deliberately narrower than what Tika itself could detect: a declared header
   * only ever names one of the admitted formats, and only where its format allows it to.
   */
  public String extensionForContentType(String contentType) {
    if (contentType == null) {
      return null;
    }
    String mediaType = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    return extensionByDeclaredContentType.get(mediaType);
  }

  /**
   * The canonical media type of {@code extension} (lower-cased, with its dot), or {@code null} for
   * an extension this system does not accept or an absent one. What a document row stores as {@code
   * content_type} once its format is decided: Tika's raw detection would report {@code .md} as
   * {@code text/plain} and a text file with header lines as {@code message/rfc822}, and downstream
   * consumers (the download endpoint, the Markdown preview) compare against the canonical type.
   */
  public String contentTypeForExtension(String extension) {
    if (extension == null) {
      return null;
    }
    return canonicalMediaTypeByExtension.get(extension.toLowerCase(Locale.ROOT));
  }

  /**
   * Whether Tika's detected {@code detectedMimeType} is consistent with a file claiming to be
   * {@code extension}; an extension this system does not admit returns {@code false}, like a {@code
   * null} detection. A {@link FormatAdmission#textTolerant() text-tolerant} extension only demands
   * anything {@link MediaTypeRegistry#isInstanceOf} recognizes as {@code text/plain}, which PDF and
   * the ZIP/OLE2 office types are not; every other extension demands one of the media types its
   * format declared.
   */
  public boolean contentMatchesExtension(String extension, String detectedMimeType) {
    if (detectedMimeType == null) {
      return false;
    }
    String normalized = detectedMimeType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    if (textTolerantExtensions.contains(extension)) {
      MediaTypeRegistry registry = MediaTypeRegistry.getDefaultRegistry();
      MediaType detected = registry.normalize(MediaType.parse(normalized));
      return detected != null && registry.isInstanceOf(detected, MediaType.TEXT_PLAIN);
    }
    Set<String> expected = detectedMediaTypesByExtension.get(extension);
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
   * The number of leading bytes {@link #detectMediaType(byte[])} needs to identify every admitted
   * type by its own signature - mirrors the 64 KiB default buffer Tika's own {@code MimeTypes}
   * magic detection reads from a stream ({@code MimeTypes#getMinLength()}). Not enough for a
   * container whose identifying part may sit past the sample; {@link #decideForPrefix} is where
   * that case is resolved.
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
  public ContentDecision decideForPrefix(
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
   * The admitted extension whose declared media types {@code detectedMimeType} matches, or {@code
   * null} otherwise - the content-only counterpart to {@link #extensionForContentType}.
   * Deliberately excludes {@link FormatAdmission#textTolerant() text-tolerant} extensions:
   * accepting any plain-text content as a "text document" regardless of its name would silently
   * widen the accepted Bestand. {@link #decideForFileName} combines the two.
   */
  public String extensionForDetectedContent(String detectedMimeType) {
    if (detectedMimeType == null) {
      return null;
    }
    return extensionByDetectedMediaType.get(
        detectedMimeType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT));
  }

  /**
   * The admitted extension that {@code fileName} ends with, or empty when it ends with none of them
   * - the file's own claimed extension, used only as a hint once {@link #decideForFileName} has
   * already decided acceptance from the content. Package-visible so {@code
   * io.opaa.library.LibraryDocumentService} can keep using its own equivalent private helper.
   */
  Optional<String> matchedExtension(String fileName) {
    if (fileName == null || fileName.isBlank()) {
      return Optional.empty();
    }
    String lowerCased = fileName.toLowerCase(Locale.ROOT);
    return extensions.stream().filter(lowerCased::endsWith).findFirst();
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
   * The single decision both indexing paths make once a file's bytes are available. A {@link
   * FormatAdmission#textTolerant() text-tolerant} extension is checked <b>first</b>, since {@code
   * text/html} is a Tika specialization of {@code text/plain} and a Markdown file opening with a
   * raw {@code <div>} would otherwise reach the HTML pipeline unreported. Otherwise a {@link
   * #extensionForDetectedContent strictly detected} type is accepted whatever the name; anything
   * else is unsupported.
   */
  public ContentDecision decideForFileName(String fileName, String detectedMimeType) {
    if (detectedMimeType == null) {
      return ContentDecision.UNSUPPORTED;
    }
    Optional<String> claimedExtension = matchedExtension(fileName);
    if (claimedExtension.isPresent()
        && textTolerantExtensions.contains(claimedExtension.get())
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
