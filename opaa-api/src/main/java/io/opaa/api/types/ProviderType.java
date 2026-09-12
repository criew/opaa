package io.opaa.api.types;

/**
 * The kind of an {@code oidc_providers} row (ADR-0033, Entscheidung 4): {@link #OIDC} is an
 * identity provider the SPA runs a code flow against; {@link #LOCAL} is the single row of the local
 * account management, whose {@code enabled} flag is that management's switch. Shared between the
 * domain and the OpenAPI schema {@code ProviderType} (ADR-0006).
 */
public enum ProviderType {
  OIDC,
  LOCAL
}
