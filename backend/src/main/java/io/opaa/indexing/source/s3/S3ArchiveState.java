package io.opaa.indexing.source.s3;

import java.util.Locale;
import java.util.Set;

/**
 * Which objects need a restore before {@code GetObject} answers (ADR-0027, Entscheidung 5): the
 * {@code GLACIER} and {@code DEEP_ARCHIVE} classes, and an {@code INTELLIGENT_TIERING} object whose
 * archive status says it moved to an archive tier - unless a completed restore ({@code
 * x-amz-restore: ongoing-request="false", ...}) made a temporary copy readable. {@code GLACIER_IR}
 * reads synchronously and is not an archive.
 */
final class S3ArchiveState {

  private static final Set<String> ARCHIVE_CLASSES = Set.of("GLACIER", "DEEP_ARCHIVE");
  private static final Set<String> ARCHIVE_STATUSES =
      Set.of("ARCHIVE_ACCESS", "DEEP_ARCHIVE_ACCESS");

  private S3ArchiveState() {}

  static boolean isArchiveClass(String storageClass) {
    return storageClass != null && ARCHIVE_CLASSES.contains(storageClass.toUpperCase(Locale.ROOT));
  }

  static boolean isArchived(String storageClass, String archiveStatus, String restore) {
    boolean archive =
        isArchiveClass(storageClass)
            || (archiveStatus != null
                && ARCHIVE_STATUSES.contains(archiveStatus.toUpperCase(Locale.ROOT)));
    if (!archive) {
      return false;
    }
    return restore == null || !restore.contains("ongoing-request=\"false\"");
  }
}
