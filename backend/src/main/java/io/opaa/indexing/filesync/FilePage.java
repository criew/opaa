package io.opaa.indexing.filesync;

import java.util.List;

/**
 * One page of a container's listing.
 *
 * @param next the continuation for the following page, {@code null} on the last one; valid only
 *     within the store's own run
 * @param unchangedSubtrees {@code file_path} prefixes the store did not list because it knows them
 *     unchanged; every stored document below one counts as present
 */
public record FilePage(List<FileEntry> entries, String next, List<String> unchangedSubtrees) {

  public FilePage {
    entries = List.copyOf(entries);
    unchangedSubtrees = unchangedSubtrees == null ? List.of() : List.copyOf(unchangedSubtrees);
  }

  public FilePage(List<FileEntry> entries, String next) {
    this(entries, next, List.of());
  }
}
