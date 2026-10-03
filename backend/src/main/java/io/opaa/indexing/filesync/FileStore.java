package io.opaa.indexing.filesync;

import io.opaa.sourceaccess.SourceRequestMeter;
import java.util.List;
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
   * One page of {@code container}'s files, starting from {@code continuation} ({@code null} for the
   * first page). Every file in the container appears on exactly one page unless it lies below one
   * of the page's {@link FilePage#unchangedSubtrees()}.
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
