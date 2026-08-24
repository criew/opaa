package io.opaa.library;

import io.opaa.indexing.Document;

/**
 * A {@link Document} paired with its already-resolved folder path (#821) - {@code folderPath} is
 * always derived by the caller, never stored, see {@link LibraryFolderPaths}.
 */
public record LibraryDocumentEntry(Document document, String folderPath) {}
