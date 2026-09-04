package io.opaa.indexing.source;

import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryFolderService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mirrors a run-based source's directory structure into {@code library_folders} (ADR-0020) - one
 * instance per indexing run, shared by the {@code FILESYSTEM} and {@code HTTP_DIRECTORY} executors
 * so both drive {@link LibraryFolderService#materializeFolderPath}/{@link
 * LibraryFolderService#pruneOrphanedFolders} through the same cache-and-prune sequence instead of
 * two copies of it (#1277).
 *
 * <p>Not thread-safe and deliberately not a bean: it carries the state of exactly one run (which
 * path segments were already resolved, which folders that run actually used).
 */
public class SourceFolderMirror {

  private static final Logger log = LoggerFactory.getLogger(SourceFolderMirror.class);

  private final LibraryFolderService folderService;
  private final KnowledgeLibrary library;

  /**
   * One {@link LibraryFolderService#materializeFolderPath} call per distinct path this run visits,
   * not one per file - a single directory can hold thousands of entries, and each call is a SELECT
   * per path segment.
   */
  private final Map<List<String>, UUID> folderIdBySegments = new HashMap<>();

  private final Set<UUID> seenFolderIds = new HashSet<>();

  public SourceFolderMirror(LibraryFolderService folderService, KnowledgeLibrary library) {
    this.folderService = folderService;
    this.library = library;
  }

  /**
   * @return the id of the deepest folder in {@code segments}, or {@code null} for an empty list
   *     (the library's root)
   */
  public UUID folderFor(List<String> segments) {
    if (segments.isEmpty()) {
      return null;
    }
    return folderIdBySegments.computeIfAbsent(
        List.copyOf(segments), key -> folderService.materializeFolderPath(library, key));
  }

  /**
   * Records that {@code folderId} actually holds content of this run, exempting it from {@link
   * #prune()}. {@code null} (the library's root) is ignored.
   */
  public void markSeen(UUID folderId) {
    if (folderId != null) {
      seenFolderIds.add(folderId);
    }
  }

  /**
   * Removes every folder of this library this run neither touched nor left holding a document -
   * only valid once the run's own document cleanup has finished, so a folder emptied by that
   * cleanup is pruned in the same run. A failure here is logged, never rethrown: it must not turn
   * an otherwise-successful document run into a FAILED job.
   */
  public void prune() {
    try {
      folderService.pruneOrphanedFolders(library, seenFolderIds);
    } catch (Exception e) {
      log.warn("Failed to prune orphaned folders for library {}", library.getId(), e);
    }
  }
}
