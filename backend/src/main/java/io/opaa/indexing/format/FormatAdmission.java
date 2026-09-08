package io.opaa.indexing.format;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * One extension a {@link DocumentFormat} admits for indexing, together with the media types that
 * belong to it. {@link SupportedDocumentFormats} is the union of these declarations over every
 * registered format and holds no format list of its own, which is what makes a new format cost one
 * class and one bean.
 *
 * <p>Every media type here is declared in the same normalized form every lookup uses - lower-cased,
 * without parameters. A declaration that is not gets rejected at construction rather than admitting
 * an extension no content can ever match.
 *
 * @param extension the accepted extension, lower-cased and with its dot ({@code ".pdf"})
 * @param canonicalMediaType what a document row stores as its {@code content_type} once this
 *     extension is decided - not necessarily what Tika detects (Markdown detects as {@code
 *     text/plain}), because the download endpoint and the preview compare against this type
 * @param detectedMediaTypes the Tika detections consistent with a file claiming {@code extension} -
 *     empty exactly when {@link #textTolerant}, and otherwise always including {@link
 *     #canonicalMediaType}
 * @param textTolerant whether content is only checked for being text at all, for a format whose
 *     bytes are not distinguishable from plain text ({@code .md} against {@code .txt}). Declared by
 *     the format because it is a property of the format; what tolerance <em>means</em> is decided
 *     once, in {@link SupportedDocumentFormats#contentMatchesExtension}.
 * @param namesItsMediaTypes whether a detected or declared media type of this admission resolves
 *     back to <em>this</em> extension. False for a second spelling of the same format ({@code .htm}
 *     beside {@code .html}), which is admitted and matched like the first but never the answer to
 *     "which extension is this content?" - see {@link #asAlternateSpelling()}
 * @param namedByDeclaredContentType whether a declared {@code Content-Type} header may name this
 *     extension for a source that carries none in its URL - see {@link
 *     #notNamedByDeclaredContentType()}
 */
public record FormatAdmission(
    String extension,
    String canonicalMediaType,
    Set<String> detectedMediaTypes,
    boolean textTolerant,
    boolean namesItsMediaTypes,
    boolean namedByDeclaredContentType) {

  public FormatAdmission {
    if (extension == null || !extension.startsWith(".") || extension.length() < 2) {
      throw new IllegalArgumentException("Extension must start with a dot: " + extension);
    }
    if (!extension.equals(extension.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("Extension must be lower-cased: " + extension);
    }
    requireNormalized(canonicalMediaType, extension);
    detectedMediaTypes = Set.copyOf(detectedMediaTypes);
    detectedMediaTypes.forEach(mediaType -> requireNormalized(mediaType, extension));
    if (textTolerant != detectedMediaTypes.isEmpty()) {
      throw new IllegalArgumentException(
          "A text-tolerant admission declares no detected media types, a strict one at least its"
              + " canonical type: "
              + extension);
    }
    if (!textTolerant && !detectedMediaTypes.contains(canonicalMediaType)) {
      throw new IllegalArgumentException(
          "The canonical media type is one of the detected ones, or content of that very type would"
              + " not match "
              + extension);
    }
  }

  /**
   * Rejects a media type that is not already in the form every lookup normalizes to - lower-cased,
   * without parameters or surrounding whitespace. Without this, a declaration like {@code
   * Application/X-Foo} would leave its extension admitted but unmatchable by any content, and would
   * reach a document row as its {@code content_type}.
   */
  private static void requireNormalized(String mediaType, String extension) {
    if (mediaType == null || mediaType.isBlank()) {
      throw new IllegalArgumentException("Missing media type for " + extension);
    }
    String normalized = mediaType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    if (!mediaType.equals(normalized)) {
      throw new IllegalArgumentException(
          "Media type of "
              + extension
              + " must be declared lower-cased and without parameters: "
              + mediaType);
    }
  }

  /**
   * A format Tika tells apart by its own byte signature: {@code canonicalMediaType} plus any
   * further detection that means the same format ({@code application/xhtml+xml} for {@code .html}).
   * A file claiming {@code extension} whose content detects as none of them is rejected outright.
   */
  public static FormatAdmission detectedAs(
      String extension, String canonicalMediaType, String... furtherDetectedMediaTypes) {
    Set<String> detected = new LinkedHashSet<>();
    detected.add(canonicalMediaType);
    Collections.addAll(detected, furtherDetectedMediaTypes);
    return new FormatAdmission(extension, canonicalMediaType, detected, false, true, true);
  }

  /**
   * A format whose content is only checked for being text at all - see {@link #textTolerant}. Its
   * {@code canonicalMediaType} still names the format ({@code text/markdown}), it is just never
   * what a detection has to report.
   */
  public static FormatAdmission textTolerant(String extension, String canonicalMediaType) {
    return new FormatAdmission(extension, canonicalMediaType, Set.of(), true, true, true);
  }

  /**
   * The same admission as a <em>second spelling</em> of a format already admitted under another
   * extension ({@code .htm} beside {@code .html}): accepted and matched exactly like the first, but
   * a detected or declared media type keeps naming the first. Only one admission of a format may
   * name a given media type; that is what keeps {@link
   * SupportedDocumentFormats#extensionForDetectedContent} an answer of the declaration rather than
   * of iteration order.
   */
  public FormatAdmission asAlternateSpelling() {
    return new FormatAdmission(
        extension, canonicalMediaType, detectedMediaTypes, textTolerant, false, false);
  }

  /**
   * The same admission, but never named by a declared {@code Content-Type} header - for a format
   * whose media type is no trustworthy name for a downloaded file ({@code message/rfc822} is Tika's
   * textual heuristic, not a byte signature). Only the two sources without an extension in their
   * address consult that mapping (the Government Site Builder attachment profile, an S3 key without
   * a suffix); the format's own file always carries its extension.
   */
  public FormatAdmission notNamedByDeclaredContentType() {
    return new FormatAdmission(
        extension, canonicalMediaType, detectedMediaTypes, textTolerant, namesItsMediaTypes, false);
  }
}
