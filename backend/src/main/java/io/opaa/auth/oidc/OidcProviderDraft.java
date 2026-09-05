package io.opaa.auth.oidc;

/**
 * The editable fields of a provider as a caller hands them to {@link OidcProviderService} (#1329) -
 * the domain-side counterpart of the generated {@code OidcProviderRequest} (ADR-0006: services
 * never see API DTOs). {@code jwkSetUri} and every optional claim field may be {@code null}.
 */
public record OidcProviderDraft(
    String displayName,
    String issuerUri,
    String clientId,
    String jwkSetUri,
    OidcClaimMapping claimMapping) {}
