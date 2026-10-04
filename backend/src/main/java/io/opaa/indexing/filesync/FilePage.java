package io.opaa.indexing.filesync;

import java.util.List;
import java.util.Map;

/**
 * One page of a container's listing. A folder is named by the hierarchy path its documents carry
 * ({@link io.opaa.knowledge.SourceDocumentContext#hierarchyPath()}), {@code ""} for the container's
 * root.
 *
 * @param next the continuation for the following page, {@code null} on the last one; valid only
 *     within the store's own run
 * @param checkpoint where a later run {@linkplain FileStore#resume resumes}: it receives every file
 *     this page and the ones before did not deliver; {@code null} when the store cannot say. Never
 *     a secret and never an address; at most {@link #MAX_CHECKPOINT_LENGTH} characters are kept
 * @param unchangedSubtrees folders the store did not list because their marker equals the one
 *     {@link FileStore#recall} handed it; every stored document of the container in or below one
 *     counts as present
 * @param listedSubtrees the folders this page listed, each with the marker the store saw - what the
 *     next run is {@linkplain FileStore#recall recalled}
 */
public record FilePage(
    List<FileEntry> entries,
    String next,
    String checkpoint,
    List<String> unchangedSubtrees,
    Map<String, String> listedSubtrees) {

  /** The longest checkpoint the core keeps; a longer one counts as none. */
  public static final int MAX_CHECKPOINT_LENGTH = 1_000_000;

  public FilePage {
    entries = List.copyOf(entries);
    unchangedSubtrees = unchangedSubtrees == null ? List.of() : List.copyOf(unchangedSubtrees);
    listedSubtrees = listedSubtrees == null ? Map.of() : Map.copyOf(listedSubtrees);
  }

  public FilePage(List<FileEntry> entries, String next) {
    this(entries, next, null, List.of(), Map.of());
  }

  public FilePage(List<FileEntry> entries, String next, String checkpoint) {
    this(entries, next, checkpoint, List.of(), Map.of());
  }

  public FilePage(
      List<FileEntry> entries,
      String next,
      List<String> unchangedSubtrees,
      Map<String, String> listedSubtrees) {
    this(entries, next, null, unchangedSubtrees, listedSubtrees);
  }
}
