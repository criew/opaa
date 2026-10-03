package io.opaa.indexing.source.googledrive;

import java.util.Locale;
import java.util.Map;

/**
 * How a Google file becomes a document (ADR-0040, Entscheidung 8): Docs as docx, Slides as pptx,
 * Sheets as xlsx; over the export limit Docs and Slides again as plain text, Sheets not at all.
 * Every other Google type has no export the formats read.
 */
final class GoogleFormats {

  static final String GOOGLE_PREFIX = "application/vnd.google-apps.";

  /** One export: the requested media type and the extension the file name gets. */
  record Export(String mediaType, String extension, boolean textFallback) {}

  static final Export TEXT = new Export("text/plain", "txt", false);

  private static final Map<String, Export> EXPORTS =
      Map.of(
          "application/vnd.google-apps.document",
          new Export(
              "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
              "docx",
              true),
          "application/vnd.google-apps.presentation",
          new Export(
              "application/vnd.openxmlformats-officedocument.presentationml.presentation",
              "pptx",
              true),
          "application/vnd.google-apps.spreadsheet",
          new Export(
              "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx", false));

  private GoogleFormats() {}

  static boolean isGoogleFile(String mimeType) {
    return mimeType != null && mimeType.startsWith(GOOGLE_PREFIX);
  }

  /** The export of a Google file, {@code null} for a type without one. */
  static Export exportOf(String mimeType) {
    return EXPORTS.get(mimeType);
  }

  /** {@code name} with {@code extension}, unless it already ends so. */
  static String withExtension(String name, String extension) {
    return name.toLowerCase(Locale.ROOT).endsWith("." + extension) ? name : name + "." + extension;
  }
}
