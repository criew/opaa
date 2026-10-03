package io.opaa.indexing.filesync;

import io.opaa.sourceaccess.SourceRequestMeter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The port a file connector implements for {@link FileSync}: one open connection per run, bound to
 * one library and closed with the run. Every failure surfaces as a {@link FileAccessException}; a
 * request over the run's budget is refused with {@link
 * io.opaa.indexing.job.RequestBudgetExhaustedException} before it is sent. The store owns
 * credentials, listing, identity, folder chain and change feature; it knows nothing of documents.
 */
public interface FileStore extends AutoCloseable {

  /** The configured containers, in configuration order. */
  List<FileContainer> containers();

  /**
   * The folder markers the last complete full sync remembered for {@code container}, keyed by
   * hierarchy path ({@link FilePage}), handed over before its first page; empty when there are none
   * or they no longer apply. Ignored by default.
   *
   * <p>A store that reports folders keeps this contract: a folder's marker changes whenever
   * anything in or below it changes - content, a name, a file or folder added, removed, renamed or
   * moved. A folder may be reported unchanged only when its marker equals the one handed over for
   * exactly its path; a renamed or moved folder has no marker at its new path and is listed. {@link
   * FileSync} hands over no marker for a folder holding a document awaiting a visit or after a
   * document was removed outside a run, and remembers none for a folder whose path a row cannot
   * carry uncut.
   */
  default void recall(FileContainer container, Map<String, String> subtreeMarkers) {}

  /**
   * One page of {@code container}'s files, starting from {@code continuation} ({@code null} for the
   * first page). Every file in the container appears on exactly one page unless it lies in one of
   * the page's {@link FilePage#unchangedSubtrees()}. A store that reports subtrees gives every
   * entry the container's key as {@link FileEntry#context()} container key, and none of its folder
   * names contains {@link io.opaa.knowledge.SourceDocumentContext#HIERARCHY_SEPARATOR}.
   *
   * @throws FileAccessException.ContainerUnlistable when the container cannot be listed completely
   */
  FilePage list(FileContainer container, String continuation)
      throws FileAccessException, InterruptedException;

  /**
   * The current state of one file - for a reported change, or for an entry whose name says nothing
   * about its format, when the {@link FileEntry#mediaType()} decides. An entry with {@link
   * Exclusion.Deselected} is skipped without a download and without counting as present.
   *
   * @throws FileAccessException.Gone when the file no longer exists
   */
  FileEntry head(FileContainer container, String id)
      throws FileAccessException, InterruptedException;

  /**
   * Downloads or exports {@code entry} into a temporary file <b>the caller deletes</b>, capped at
   * {@code maxBytes} while streaming - a partial file never survives.
   *
   * @throws FileAccessException.TooLarge when the file exceeds {@code maxBytes}
   */
  FetchedFile fetch(FileEntry entry, long maxBytes)
      throws FileAccessException, InterruptedException;

  /** The store's change log, empty for a store that only lists. */
  default Optional<ChangeFeed> changes() {
    return Optional.empty();
  }

  /** What this store did so far: requests, throttles, bytes. */
  SourceRequestMeter meter();

  @Override
  void close();
}
