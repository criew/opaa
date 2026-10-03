package io.opaa.indexing.filesync;

import java.time.Duration;

/**
 * The bounds of one {@link FileSync}, from the connector's own properties.
 *
 * @param maxFileSizeBytes the size bound checked against the listed size and the transfer
 * @param maxEntriesPerRun listed entries per run before the run fails visibly
 * @param downloadConcurrency downloads in flight while the listing goes on; {@code 1} is serial
 * @param downloadThreadPrefix the name prefix of the download threads, followed by the library id
 * @param subtreeMemoryMaxAge how long remembered folder markers may spare a listing before one full
 *     sync lists every folder again; {@code null} for no limit
 */
public record FileSyncSettings(
    long maxFileSizeBytes,
    long maxEntriesPerRun,
    int downloadConcurrency,
    String downloadThreadPrefix,
    Duration subtreeMemoryMaxAge) {

  /** Without a limit on remembered folder markers - for a store that reports none. */
  public FileSyncSettings(
      long maxFileSizeBytes,
      long maxEntriesPerRun,
      int downloadConcurrency,
      String downloadThreadPrefix) {
    this(maxFileSizeBytes, maxEntriesPerRun, downloadConcurrency, downloadThreadPrefix, null);
  }
}
