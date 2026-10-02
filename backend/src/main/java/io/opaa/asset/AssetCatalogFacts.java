package io.opaa.asset;

/**
 * What the catalog shows of one asset beyond the shell: its state by its type's own measure and the
 * facts only its type has. Each type with such facts implements this with its own record.
 */
public interface AssetCatalogFacts {

  /** The type's own state - never {@link AssetCatalogStatus#SUCCESSION_OPEN}. */
  AssetCatalogStatus status();
}
