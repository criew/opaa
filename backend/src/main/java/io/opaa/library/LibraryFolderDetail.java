package io.opaa.library;

/**
 * A {@link LibraryFolder} enriched with its recursive document count (#820) - its own documents
 * plus every document in every one of its descendant folders. The domain counterpart of the
 * generated {@code LibraryFolderResponse}, returned by {@link LibraryFolderService#createFolder},
 * {@link LibraryFolderService#renameFolder} and {@link LibraryFolderService#getFolder}.
 */
public record LibraryFolderDetail(LibraryFolder folder, long documentCount) {}
