package io.opaa.api.types;

/**
 * Public or restricted (ADR-0039, Entscheidung 2): derived from an asset's grants, never stored.
 * {@link #PUBLIC} means an unexpired grant to all accounts.
 */
public enum CatalogVisibility {
  PUBLIC,
  RESTRICTED
}
