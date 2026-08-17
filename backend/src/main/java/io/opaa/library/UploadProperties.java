package io.opaa.library;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the REST document upload endpoint (#420,
 * docs/features/knowledge-sources.md#upload).
 *
 * @param storagePath filesystem path where uploaded files are stored, one subdirectory per library
 *     (by library id) so files from different libraries never collide and a library's uploads can
 *     be told apart on disk. Deliberately separate from {@code opaa.indexing.document-path}: that
 *     directory is crawled by the filesystem indexing path (#207) and is operator-managed, whereas
 *     this one is written to exclusively by {@link LibraryDocumentService}.
 * @param maxFileSize maximum accepted upload size in bytes. Default 50 MiB (52 428 800): generous
 *     enough for a typical scanned Dienstanweisung while still bounding memory and disk use per
 *     upload; see #420's acceptance criteria for the resulting 413 response.
 */
@ConfigurationProperties(prefix = "opaa.upload")
public record UploadProperties(String storagePath, long maxFileSize) {

  public UploadProperties {
    if (storagePath == null || storagePath.isBlank()) {
      storagePath = "./uploads";
    }
    if (maxFileSize <= 0) {
      maxFileSize = 50L * 1024 * 1024;
    }
  }
}
