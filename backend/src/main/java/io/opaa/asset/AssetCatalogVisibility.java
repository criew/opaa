package io.opaa.asset;

/**
 * Public or restricted (ADR-0039, Entscheidung 2): derived from the grants, never stored. Public
 * means an unexpired grant to all accounts.
 */
public enum AssetCatalogVisibility {
  PUBLIC,
  RESTRICTED
}
