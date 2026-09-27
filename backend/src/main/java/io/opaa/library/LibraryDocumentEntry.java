package io.opaa.library;

import io.opaa.knowledge.Document;

/**
 * A {@link Document} paired with its already-resolved folder path (#821) and the address a reader
 * may open for it - both derived by the caller, never stored: {@code folderPath} via {@link
 * LibraryFolderPaths}, {@code sourceUrl} by the document's connector ({@code null} where there is
 * none).
 */
public record LibraryDocumentEntry(Document document, String folderPath, String sourceUrl) {

  /** An entry without a readable source address. */
  public LibraryDocumentEntry(Document document, String folderPath) {
    this(document, folderPath, null);
  }
}
