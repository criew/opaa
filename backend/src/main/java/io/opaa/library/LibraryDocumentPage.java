package io.opaa.library;

import java.util.List;
import java.util.UUID;

/**
 * A page of a library's documents (#821), folder-aware since Epic #520 Phase 2 (ADR-0020) - the
 * domain counterpart of the generated {@code LibraryDocumentPageResponse}, returned by {@link
 * KnowledgeLibraryService#listDocuments}.
 *
 * @param folders the browsed folder's direct subfolders; empty while searching ({@code q} set).
 * @param breadcrumb the browsed folder's ancestor chain, root-first; empty for the library's root
 *     or while searching.
 * @param folderId the folder actually browsed; {@code null} while searching.
 */
public record LibraryDocumentPage(
    List<LibraryDocumentEntry> documents,
    int page,
    int size,
    long totalElements,
    List<LibraryFolderChild> folders,
    List<LibraryFolder> breadcrumb,
    UUID folderId) {}
