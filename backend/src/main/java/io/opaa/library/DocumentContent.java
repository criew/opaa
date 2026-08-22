package io.opaa.library;

import java.nio.file.Path;

/**
 * The resolved, on-disk original of an indexed document (#736) - what {@link
 * LibraryDocumentService#loadContent} hands back to {@code DocumentController#getDocumentContent}
 * once every access, sourceType and traversal check has passed. {@code fileName} is the document's
 * own display name (never a caller-influenced path), {@code contentType} the value to answer the
 * response with - the document's own stored {@code contentType} where present, a probed fallback
 * otherwise.
 */
public record DocumentContent(Path path, String fileName, String contentType) {}
