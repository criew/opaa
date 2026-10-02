package io.opaa.library;

import io.opaa.asset.AssetCatalogFacts;
import io.opaa.asset.AssetCatalogStatus;
import java.time.Instant;

/**
 * What a knowledge library shows on its catalog tile.
 *
 * @param sourceType the key of the library's source type.
 * @param lastIndexedAt completion of the newest successful run; {@code null} while none completed.
 * @param status by the newest indexing run, see {@link KnowledgeLibraryCatalogFactSource}.
 */
public record KnowledgeLibraryCatalogFacts(
    String sourceType, Instant lastIndexedAt, AssetCatalogStatus status)
    implements AssetCatalogFacts {}
