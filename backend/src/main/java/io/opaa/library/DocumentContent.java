package io.opaa.library;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * The resolved original of an indexed document (#736) - what {@link
 * LibraryDocumentService#loadContent} hands back to {@code DocumentController#getDocumentContent}
 * once every access, sourceType and traversal check has passed. {@code fileName} is the document's
 * own display name (never a caller-influenced path), {@code contentType} the value to answer the
 * response with - the document's own stored {@code contentType} where present, a probed/remote
 * fallback otherwise.
 *
 * <p>Exactly one of {@code path}/{@code stream} is set. {@code path} names an operator-managed file
 * on local disk ({@code UPLOAD}/{@code FILESYSTEM}) the controller reads via {@link
 * org.springframework.core.io.FileSystemResource} - unowned by this request, left untouched once
 * served. {@code stream} (#747/#748 review, finding 3) is the still-open body of a {@code
 * HTTP_DIRECTORY}/{@code RSS_FEED} document proxied live from its remote source (see {@code
 * LibraryDocumentService#loadRemoteContent}) - streamed straight to the caller via {@link
 * org.springframework.core.io.InputStreamResource} instead of first being buffered into a temp file
 * this class or the controller would then have to remember to delete. This closes the concrete leak
 * paths a temp-file-plus-delete-on-close approach had (an exception before the stream is ever
 * opened, a Range request that never calls {@code getInputStream()}) simply by never creating a
 * file in the first place - there is nothing left over to sweep.
 */
public record DocumentContent(Path path, InputStream stream, String fileName, String contentType) {

  public DocumentContent {
    if ((path == null) == (stream == null)) {
      throw new IllegalArgumentException("Exactly one of path/stream must be set");
    }
  }

  /** A local, operator-managed original at {@code path} ({@code UPLOAD}/{@code FILESYSTEM}). */
  public DocumentContent(Path path, String fileName, String contentType) {
    this(path, null, fileName, contentType);
  }

  /**
   * A live, still-open remote body streamed directly to the caller (#747/#748) - the caller (here:
   * {@code DocumentController}) is responsible for closing {@code stream} once it has been written
   * to the response, or the request is aborted.
   */
  public static DocumentContent ofStream(InputStream stream, String fileName, String contentType) {
    return new DocumentContent(null, stream, fileName, contentType);
  }

  /** Whether this content is a live remote stream rather than a local file at {@link #path()}. */
  public boolean isStreamed() {
    return stream != null;
  }
}
