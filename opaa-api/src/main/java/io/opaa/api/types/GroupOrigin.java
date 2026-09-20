package io.opaa.api.types;

/**
 * Where a group comes from (ADR-0036, Entscheidung 2). Derived from {@code groups.provider_id}
 * rather than stored: a group either belongs to an identity provider or is this installation's own.
 */
public enum GroupOrigin {
  INTERNAL,
  PROVIDER
}
