package io.opaa.indexing;

/**
 * The {@code file_path} identity of an attachment (ADR-0022, Entscheidung 2): {@code
 * <parentFilePath>/<index>/<fileName>}, {@code index} being its 0-based extraction position in the
 * parent. Parent path and index keep identically-named attachments apart, no real file can carry
 * this shape because {@code parentFilePath} names a file, and nesting chains naturally - an inner
 * message's own path becomes its attachments' parent path.
 */
public final class AttachmentFilePath {

  private AttachmentFilePath() {}

  public static String of(String parentFilePath, int index, String fileName) {
    return parentFilePath + "/" + index + "/" + fileName;
  }

  /**
   * The inverse of {@link #of}: the extraction-order index encoded in {@code attachmentPath}, or
   * {@code -1} when it does not have that shape (a parent path that changed since, a malformed
   * row).
   */
  public static int indexIn(String parentFilePath, String attachmentPath) {
    String prefix = parentFilePath + "/";
    if (attachmentPath == null || !attachmentPath.startsWith(prefix)) {
      return -1;
    }
    String remainder = attachmentPath.substring(prefix.length());
    int slash = remainder.indexOf('/');
    if (slash <= 0) {
      return -1;
    }
    try {
      return Integer.parseInt(remainder.substring(0, slash));
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
