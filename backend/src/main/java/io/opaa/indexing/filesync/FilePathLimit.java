package io.opaa.indexing.filesync;

import java.nio.charset.StandardCharsets;

/**
 * The longest {@code documents.file_path} a document can be stored under: 2000 characters (the
 * column) and 2676 UTF-8 bytes (one entry of the unique index on {@code (library_id, file_path)}
 * for a path the database cannot compress). A store whose file path grows with the source path
 * reports a longer one as {@link Exclusion.Unavailable}, under its {@link #cut} as file path.
 */
public final class FilePathLimit {

  public static final int MAX_CHARACTERS = 2000;
  public static final int MAX_BYTES = 2676;

  private FilePathLimit() {}

  public static boolean fits(String filePath) {
    return filePath.length() <= MAX_CHARACTERS
        && filePath.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
  }

  /** The longest prefix of {@code filePath} that {@link #fits}, never splitting a code point. */
  public static String cut(String filePath) {
    int characters = 0;
    int bytes = 0;
    int end = 0;
    while (end < filePath.length()) {
      int codePoint = filePath.codePointAt(end);
      int width = Character.charCount(codePoint);
      int encoded = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
      if (characters + width > MAX_CHARACTERS || bytes + encoded > MAX_BYTES) {
        break;
      }
      characters += width;
      bytes += encoded;
      end += width;
    }
    return filePath.substring(0, end);
  }
}
