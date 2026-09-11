package io.opaa.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The content type a local original is served with. The row's own type - decided at index time - is
 * the primary source, so serving never makes a second, independent guess from the bytes that could
 * disagree with what was actually indexed; probing is only the fallback for a row without one.
 * {@code DocumentController}'s Content-Security-Policy and X-Content-Type-Options headers are what
 * keep that type from becoming a script execution vector, not this choice of source.
 */
final class ServedContentTypes {

  private static final String FALLBACK = "application/octet-stream";

  private ServedContentTypes() {}

  static String forFile(String declaredContentType, Path file) {
    String contentType = declaredContentType;
    if (contentType == null || contentType.isBlank()) {
      try {
        contentType = Files.probeContentType(file);
      } catch (IOException e) {
        contentType = null;
      }
    }
    return contentType == null || contentType.isBlank() ? FALLBACK : contentType;
  }
}
