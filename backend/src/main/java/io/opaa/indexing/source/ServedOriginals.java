package io.opaa.indexing.source;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

/** Building blocks for serving an original that the connectors and the administration share. */
public final class ServedOriginals {

  private static final Logger log = LoggerFactory.getLogger(ServedOriginals.class);

  private ServedOriginals() {}

  /**
   * A source-declared {@code Content-Type} reduced to its bare {@code type/subtype}, or {@code
   * null} for a missing or invalid one - a parameter cannot smuggle a value past a caller comparing
   * it verbatim, and a malformed header never turns into a 500 downstream.
   */
  public static String normalizeContentType(String rawContentType) {
    if (rawContentType == null || rawContentType.isBlank()) {
      return null;
    }
    try {
      MediaType parsed = MediaType.parseMediaType(rawContentType);
      return new MediaType(parsed.getType(), parsed.getSubtype()).toString();
    } catch (InvalidMediaTypeException e) {
      log.debug("Remote source declared an invalid Content-Type: {}", rawContentType, e);
      return null;
    }
  }

  /** {@code stream}, with every file in {@code tempFiles} deleted once it is closed. */
  public static InputStream deletingOnClose(InputStream stream, List<Path> tempFiles) {
    List<Path> toDelete = List.copyOf(tempFiles);
    return new FilterInputStream(stream) {
      @Override
      public void close() throws IOException {
        try {
          super.close();
        } finally {
          toDelete.forEach(ServedOriginals::deleteQuietly);
        }
      }
    };
  }

  public static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      log.warn("Could not delete file {}", path, e);
    }
  }
}
