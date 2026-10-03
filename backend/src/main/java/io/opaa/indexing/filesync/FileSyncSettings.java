package io.opaa.indexing.filesync;

/**
 * The bounds of one {@link FileSync}, from the connector's own properties.
 *
 * @param maxFileSizeBytes the size bound checked against the listed size and the transfer
 * @param maxEntriesPerRun listed entries per run before the run fails visibly
 * @param downloadConcurrency downloads in flight while the listing goes on; {@code 1} is serial
 * @param downloadThreadPrefix the name prefix of the download threads, followed by the library id
 */
public record FileSyncSettings(
    long maxFileSizeBytes,
    long maxEntriesPerRun,
    int downloadConcurrency,
    String downloadThreadPrefix) {}
