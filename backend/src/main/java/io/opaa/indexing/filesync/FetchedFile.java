package io.opaa.indexing.filesync;

import java.nio.file.Path;

/**
 * A downloaded or exported file: the temporary file <b>the caller deletes</b>.
 *
 * @param size the bytes actually written to {@code file}
 * @param changeMarker the change feature the transfer reported, stored when the listing had none
 */
public record FetchedFile(Path file, long size, String changeMarker) {}
