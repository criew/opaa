package io.opaa.indexing.filesync;

import java.nio.file.Path;

/**
 * A downloaded or exported file: the temporary file <b>the caller deletes</b>.
 *
 * @param size the bytes actually written to {@code file}
 * @param changeMarker the change feature the transfer reported, stored when the listing had none
 * @param fileName the name the format is judged by when the store fetched another format than the
 *     listing named (a text export instead of an office file), {@code null} to keep the listed one
 * @param note a German protocol note on such a fetch, {@code null} for none
 */
public record FetchedFile(Path file, long size, String changeMarker, String fileName, String note) {

  public FetchedFile(Path file, long size, String changeMarker) {
    this(file, size, changeMarker, null, null);
  }
}
