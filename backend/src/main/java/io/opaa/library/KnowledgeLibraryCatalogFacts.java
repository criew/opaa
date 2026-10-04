package io.opaa.library;

import io.opaa.api.types.CatalogEntryStatus;
import io.opaa.asset.AssetCatalogFacts;
import io.opaa.indexing.source.SourceBlock;
import java.time.Instant;

/**
 * What a knowledge library shows on its catalog tile.
 *
 * @param sourceType the key of the library's source type.
 * @param lastIndexedAt completion of the newest successful run; {@code null} while none completed.
 * @param status the library's own state, see {@link KnowledgeLibraryCatalogFactSource}.
 * @param sourceBlock the lock of the source, {@code null} while it is not locked
 * @param privateLibrary whether only its owner reads it
 */
public record KnowledgeLibraryCatalogFacts(
    String sourceType,
    Instant lastIndexedAt,
    CatalogEntryStatus status,
    SourceBlock sourceBlock,
    boolean privateLibrary)
    implements AssetCatalogFacts {

  /** The facts of a shared library that is not locked. */
  public KnowledgeLibraryCatalogFacts(
      String sourceType, Instant lastIndexedAt, CatalogEntryStatus status) {
    this(sourceType, lastIndexedAt, status, null, false);
  }

  /** The facts of a shared library. */
  public KnowledgeLibraryCatalogFacts(
      String sourceType,
      Instant lastIndexedAt,
      CatalogEntryStatus status,
      SourceBlock sourceBlock) {
    this(sourceType, lastIndexedAt, status, sourceBlock, false);
  }
}
