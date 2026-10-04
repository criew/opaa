package io.opaa.library;

import io.opaa.api.types.CatalogEntryStatus;
import io.opaa.asset.AssetCatalogFacts;
import java.time.Instant;

/**
 * What a knowledge library shows on its catalog tile.
 *
 * @param sourceType the key of the library's source type.
 * @param lastIndexedAt completion of the newest successful run; {@code null} while none completed.
 * @param status the library's own state, see {@link KnowledgeLibraryCatalogFactSource}.
 * @param sourceLockNotice the note of a locked source, {@code null} while it is not locked
 */
public record KnowledgeLibraryCatalogFacts(
    String sourceType, Instant lastIndexedAt, CatalogEntryStatus status, String sourceLockNotice)
    implements AssetCatalogFacts {

  /** The facts of a library that is not locked. */
  public KnowledgeLibraryCatalogFacts(
      String sourceType, Instant lastIndexedAt, CatalogEntryStatus status) {
    this(sourceType, lastIndexedAt, status, null);
  }
}
