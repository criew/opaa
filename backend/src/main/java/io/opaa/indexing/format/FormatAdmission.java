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
 * @param extension the accepted extension, lower-cased and with its dot ({@code ".pdf"})
 * @param canonicalMediaType what a document row stores as its {@code content_type} once this
 *     extension is decided - not necessarily what Tika detects (Markdown detects as {@code
 *     text/plain}), because the download endpoint and the preview compare against this type
 * @param detectedMediaTypes the Tika detections consistent with a file claiming {@code extension},
 *     always including {@link #canonicalMediaType}; empty exactly when {@link #textTolerant}
 * @param textTolerant whether content is only checked for being text at all, for a format whose
 *     bytes are not distinguishable from plain text ({@code .md} against {@code .txt}). Declared by
 *     the format because it is a property of the format; what tolerance <em>means</em> is decided
 *     once, in {@link SupportedDocumentFormats#contentMatchesExtension}.
 */
public record FormatAdmission(
    String extension,
    String canonicalMediaType,
    Set<String> detectedMediaTypes,
    boolean textTolerant) {

  public FormatAdmission {
    if (extension == null || !extension.startsWith(".") || extension.length() < 2) {
      throw new IllegalArgumentException("Extension must start with a dot: " + extension);
    }
    if (!extension.equals(extension.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("Extension must be lower-cased: " + extension);
    }
    if (canonicalMediaType == null || canonicalMediaType.isBlank()) {
      throw new IllegalArgumentException("Missing canonical media type for " + extension);
    }
    detectedMediaTypes = Set.copyOf(detectedMediaTypes);
    if (textTolerant != detectedMediaTypes.isEmpty()) {
      throw new IllegalArgumentException(
          "A text-tolerant admission declares no detected media types, a strict one at least its"
              + " canonical type: "
              + extension);
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
    return new FormatAdmission(extension, canonicalMediaType, detected, false);
  }

  /**
   * A format whose content is only checked for being text at all - see {@link #textTolerant}. Its
   * {@code canonicalMediaType} still names the format ({@code text/markdown}), it is just never
   * what a detection has to report.
   */
  public static FormatAdmission textTolerant(String extension, String canonicalMediaType) {
    return new FormatAdmission(extension, canonicalMediaType, Set.of(), true);
  }
}
