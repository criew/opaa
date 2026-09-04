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
 * served. {@code stream} is served via {@link org.springframework.core.io.InputStreamResource} and
 * is either the still-open body of a {@code HTTP_DIRECTORY}/{@code RSS_FEED} document proxied live
 * from its remote source (#747/#748, {@code LibraryDocumentService#loadRemoteContent}) or the
 * re-extracted bytes of an attachment (ADR-0022/#1239, {@code
 * LibraryDocumentService#loadAttachmentContent}).
 *
 * <p><b>The caller closes {@code stream}, and closing it is what releases everything behind it</b>
 * - the remote connection for a proxied body, and the temp files an attachment's re-extraction
 * created, which its stream deletes on close. {@code ResourceHttpMessageConverter} closes the
 * stream in a {@code finally} block once the body has been written or the request aborted, so that
 * happens exactly once on every outcome, including a client that disconnects mid-transfer.
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
   * A still-open stream served directly to the caller - the caller (here: {@code
   * DocumentController}) is responsible for closing {@code stream} once it has been written to the
   * response, or the request is aborted.
   */
  public static DocumentContent ofStream(InputStream stream, String fileName, String contentType) {
    return new DocumentContent(null, stream, fileName, contentType);
  }

  /** Whether this content is a live remote stream rather than a local file at {@link #path()}. */
  public boolean isStreamed() {
    return stream != null;
  }
}
