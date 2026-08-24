package io.opaa.library;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Derives a folder's display path (e.g. {@code "Protokolle/2026"}) from {@link LibraryFolder}'s
 * self-referencing {@code parentFolderId} chain (#821, Epic #520 Phase 2, ADR-0020) - never stored,
 * always computed from the current folder rows, exactly as ADR-0020's Entscheidung 2 promises ("der
 * Anzeigepfad wird abgeleitet").
 *
 * <p>Two entry points for the two shapes this task needs:
 *
 * <ul>
 *   <li>{@link #loadFoldersById} + {@link #pathOf(UUID, Map)} - one query for a whole library's
 *       folders, then an in-memory walk per document. Used by {@code
 *       KnowledgeLibraryService#listDocuments}, where a page can reference many distinct folders (a
 *       bibliotheksweit {@code q} search in particular) and a per-document chain of queries would
 *       cost one round trip per ancestor per document instead of one for the whole page.
 *   <li>{@link #pathOf(LibraryFolderRepository, UUID)} - walks {@code parentFolderId} one row at a
 *       time via {@link LibraryFolderRepository#findById}, bounded the same way {@code
 *       LibraryFolderService#depthOfParentChain} already bounds its own identical walk. Used by
 *       {@code LibraryDocumentService#uploadDocument}, which only ever needs one document's path
 *       and has no reason to load an entire library's folder table for that.
 * </ul>
 */
final class LibraryFolderPaths {

  /**
   * Defensive cycle guard for {@link #pathOf(LibraryFolderRepository, UUID)}'s per-row walk -
   * mirrors {@code LibraryFolderService#depthOfParentChain}'s identical {@code MAX_DEPTH + 1}
   * bound: this class's own create/rename path can never produce a cycle, but a corrupted or
   * foreign row should fail loudly here rather than loop forever.
   */
  private static final int MAX_WALK_DEPTH = 32;

  private LibraryFolderPaths() {}

  /** Every folder of {@code libraryId}, indexed by id - see the class Javadoc. */
  static Map<UUID, LibraryFolder> loadFoldersById(
      LibraryFolderRepository repository, UUID libraryId) {
    return repository.findByLibraryId(libraryId).stream()
        .collect(Collectors.toMap(LibraryFolder::getId, Function.identity()));
  }

  /**
   * The display path of {@code folderId} using an already-loaded {@code foldersById} map - {@code
   * null} for the library's root ({@code folderId == null}) or for a {@code folderId} missing from
   * the map (should not happen given {@code fk_documents_folder}'s {@code RESTRICT}, but a stale
   * read racing a delete is a display-only concern, not worth failing the whole response over).
   */
  static String pathOf(UUID folderId, Map<UUID, LibraryFolder> foldersById) {
    if (folderId == null) {
      return null;
    }
    Deque<String> segments = new ArrayDeque<>();
    UUID current = folderId;
    int depth = 0;
    while (current != null) {
      if (++depth > MAX_WALK_DEPTH) {
        return null;
      }
      LibraryFolder folder = foldersById.get(current);
      if (folder == null) {
        return null;
      }
      segments.addFirst(folder.getName());
      current = folder.getParentFolderId();
    }
    return String.join("/", segments);
  }

  /**
   * The single-folder counterpart to {@link #pathOf(UUID, Map)} - walks {@code parentFolderId} via
   * {@code repository.findById} instead of an already-loaded map, for a caller that only ever needs
   * one folder's path (see the class Javadoc).
   */
  static String pathOf(LibraryFolderRepository repository, UUID folderId) {
    if (folderId == null) {
      return null;
    }
    Deque<String> segments = new ArrayDeque<>();
    UUID current = folderId;
    int depth = 0;
    while (current != null) {
      if (++depth > MAX_WALK_DEPTH) {
        return null;
      }
      LibraryFolder folder = repository.findById(current).orElse(null);
      if (folder == null) {
        return null;
      }
      segments.addFirst(folder.getName());
      current = folder.getParentFolderId();
    }
    return String.join("/", segments);
  }
}
