package io.opaa.indexing.filesync;

import io.opaa.indexing.source.SourceFolderPath;
import io.opaa.knowledge.SourceDocumentContext;
import java.util.Objects;

/**
 * One file as a listing, a single check or a change reports it.
 *
 * @param id the file's identity within {@code container}, what {@link FileStore#head} and {@link
 *     FileStore#fetch} address it by
 * @param filePath the document's identity in the library ({@code documents.file_path})
 * @param fileName the name the format is judged by; empty only together with an {@code exclusion}
 * @param folder the mirrored folder chain, already capped by the store
 * @param context container key and hierarchy path the document carries
 * @param size in bytes, {@code -1} when the source does not know it before the download
 * @param changeMarker the change feature compared before any download; {@code null} when the
 *     listing has none, then {@link FetchedFile#changeMarker()} is stored
 * @param mediaType the content type, {@code null} when the source reports none
 * @param exclusion why the entry is not fetched, {@code null} for an ordinary file
 */
public record FileEntry(
    FileContainer container,
    String id,
    String filePath,
    String fileName,
    SourceFolderPath folder,
    SourceDocumentContext context,
    long size,
    String changeMarker,
    String mediaType,
    Exclusion exclusion) {

  public FileEntry {
    Objects.requireNonNull(container, "container");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(filePath, "filePath");
    Objects.requireNonNull(fileName, "fileName");
    Objects.requireNonNull(folder, "folder");
    Objects.requireNonNull(context, "context");
    if (fileName.isEmpty() && exclusion == null) {
      throw new IllegalArgumentException("an entry without a file name needs an exclusion");
    }
  }
}
