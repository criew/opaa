package io.opaa.indexing.pipeline.mail;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Shared, size-bounded temp-file plumbing for {@link EmlReader} and {@link MsgReader}: an
 * attachment is written to disk under {@link MailProperties#maxAttachmentBytes()} while it is being
 * read, never after the fact.
 */
final class MailAttachmentIo {

  private MailAttachmentIo() {}

  /** Thrown when copying an attachment would exceed its configured size limit. */
  static final class AttachmentTooLargeException extends IOException {
    AttachmentTooLargeException(long maxBytes) {
      super("Mail attachment exceeds the configured size limit of " + maxBytes + " bytes");
    }
  }

  /** A fresh temp file named after {@code fileName}'s own extension, for later format detection. */
  static Path createTempFile(String fileName) throws IOException {
    return Files.createTempFile("opaa-mail-", suffixFor(fileName));
  }

  /**
   * Copies every byte of {@code in} to {@code out}, aborting with {@link
   * AttachmentTooLargeException} the moment more than {@code maxBytes} have been written - the
   * caller is responsible for deleting the partial temp file this leaves behind.
   */
  static void copyBounded(InputStream in, OutputStream out, long maxBytes) throws IOException {
    try (OutputStream bounded = boundedOutputStream(out, maxBytes)) {
      in.transferTo(bounded);
    }
  }

  /**
   * Wraps {@code out} so a write past {@code maxBytes} fails loudly instead of writing an
   * unboundedly large attachment to disk - used directly by {@link EmlReader} when re-serializing a
   * nested message (no {@link InputStream} of its own to bound via {@link #copyBounded}).
   */
  static OutputStream boundedOutputStream(OutputStream out, long maxBytes) {
    return new FilterOutputStream(out) {
      private long total;

      @Override
      public void write(int b) throws IOException {
        checkLimit(++total);
        out.write(b);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        checkLimit(total += len);
        out.write(b, off, len);
      }

      private void checkLimit(long writtenSoFar) throws IOException {
        if (writtenSoFar > maxBytes) {
          throw new AttachmentTooLargeException(maxBytes);
        }
      }
    };
  }

  /**
   * A safe {@code Files.createTempFile} suffix derived from {@code fileName}'s own extension, or
   * {@code .tmp} when there is none or it does not look like one. Restricted to {@code [A-Za-z0-9]}
   * after the dot - an attachment name is attacker-controlled content from inside the parsed
   * message, and a raw extension can carry characters {@code Files.createTempFile} rejects outright
   * on some platforms (e.g. {@code :} on Windows, throwing {@link
   * java.nio.file.InvalidPathException}) or that are otherwise unsafe to splice into a path segment
   * - an attachment named {@code "bericht.q1:2024"} must be skipped and logged like any other
   * attachment failure, never crash the whole message's extraction.
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
