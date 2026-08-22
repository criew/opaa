package io.opaa.library;

import java.nio.file.Path;

/**
 * The resolved, on-disk original of an indexed document (#736) - what {@link
 * LibraryDocumentService#loadContent} hands back to {@code DocumentController#getDocumentContent}
 * once every access, sourceType and traversal check has passed. {@code fileName} is the document's
 * own display name (never a caller-influenced path), {@code contentType} the value to answer the
 * response with - the document's own stored {@code contentType} where present, a probed fallback
 * otherwise.
 *
 * <p>{@code temporary} (#747): {@code true} for a {@code HTTP_DIRECTORY}/{@code RSS_FEED} document
 * proxied from its remote source into a freshly downloaded temp file (see {@code
 * LibraryDocumentService#loadRemoteContent}) - {@code path} then names a file whoever streams this
 * content to the caller must delete afterwards, unlike the operator-managed {@code UPLOAD}/{@code
 * FILESYSTEM} original the {@code false} default leaves untouched. The three-argument constructor
 * below keeps every existing local-file caller unchanged.
 */
public record DocumentContent(Path path, String fileName, String contentType, boolean temporary) {

  public DocumentContent(Path path, String fileName, String contentType) {
    this(path, fileName, contentType, false);
  }
}
