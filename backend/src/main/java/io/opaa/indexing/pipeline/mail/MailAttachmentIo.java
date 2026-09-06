package io.opaa.indexing.pipeline.mail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Temp-file naming for the attachments {@link EmlReader} and {@link MsgReader} extract; the size
 * ceiling they write under is {@link io.opaa.sourceaccess.BoundedStreams}'.
 */
final class MailAttachmentIo {

  private MailAttachmentIo() {}

  /** A fresh temp file named after {@code fileName}'s own extension, for later format detection. */
  static Path createTempFile(String fileName) throws IOException {
    return Files.createTempFile("opaa-mail-", suffixFor(fileName));
  }

  /**
   * A safe {@code Files.createTempFile} suffix derived from {@code fileName}'s extension, or {@code
   * .tmp} when there is none. Restricted to {@code [A-Za-z0-9]} after the dot: an attachment name
   * is attacker-controlled content, and a raw extension can carry characters the platform rejects
   * outright, which must skip that one attachment rather than crash the whole extraction.
   */
  private static String suffixFor(String fileName) {
    if (fileName == null) {
      return ".tmp";
    }
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) {
      return ".tmp";
    }
    // A "suffix" longer than this is not a real extension (e.g. a filename with no dot-separated
    // extension at all but a stray period) - Files.createTempFile would still accept it, but there
    // is no benefit in preserving it verbatim, and an attacker-controlled file name should not
    // grow the temp file name unboundedly.
    String suffix = fileName.substring(dot).toLowerCase(Locale.ROOT);
    return suffix.length() <= 10 && suffix.matches("\\.[a-z0-9]+") ? suffix : ".tmp";
  }
}
