package io.opaa.library;

import io.opaa.knowledge.Document;

/**
 * A {@link Document} paired with its already-resolved folder path, the address a reader may open
 * for it and, for an attachment, its parent's file name - all derived by the caller, never stored:
 * {@code folderPath} via {@link LibraryFolderPaths}, {@code sourceUrl} by the document's connector
 * ({@code null} where there is none), {@code parentFileName} {@code null} for a top-level document.
 */
public record LibraryDocumentEntry(
    Document document, String folderPath, String sourceUrl, String parentFileName) {

  /** An entry without a parent name. */
  public LibraryDocumentEntry(Document document, String folderPath, String sourceUrl) {
    this(document, folderPath, sourceUrl, null);
  }

  /** An entry without a readable source address. */
  public LibraryDocumentEntry(Document document, String folderPath) {
    this(document, folderPath, null);
  }
}
