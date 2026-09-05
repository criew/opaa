package io.opaa.api;

import io.opaa.api.dto.OidcClaimMappingDto;
import io.opaa.api.dto.OidcProviderRegistryState;
import io.opaa.api.dto.OidcProviderRequest;
import io.opaa.api.dto.OidcProviderResponse;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderDraft;
import io.opaa.auth.oidc.OidcProviderRegistry;

/**
 * Maps between {@link OidcProvider} and the generated DTOs (ADR-0006): the domain never sees a DTO,
 * the controller never builds an entity. A request without {@code claimMapping} (or with omitted
 * fields inside it) gets the Keycloak defaults - {@link OidcClaimMapping}'s own canonical
 * constructor fills them, so "omitted" and "blank" mean the same thing. The registry state comes
 * from {@link OidcProviderRegistry#healthOf}, never from the row.
 */
final class OidcProviderResponseMapper {

  private OidcProviderResponseMapper() {}

  static OidcProviderResponse toResponse(
      OidcProvider provider, OidcProviderRegistry.Health health) {
    OidcClaimMapping mapping = provider.getClaimMapping();
    OidcClaimMappingDto mappingDto =
        new OidcClaimMappingDto()
            .emailClaim(mapping.emailClaim())
            .displayNameClaim(mapping.displayNameClaim())
            .rolesClaim(mapping.rolesClaim())
            .systemAdminRole(mapping.systemAdminRole())
            .auditorRole(mapping.auditorRole())
            .groupsClaim(mapping.groupsClaim());
    OidcProviderRegistryState state =
        !provider.isEnabled()
            ? OidcProviderRegistryState.DISABLED
            : health.ready()
                ? OidcProviderRegistryState.READY
                : OidcProviderRegistryState.UNAVAILABLE;
    return new OidcProviderResponse(
            provider.getId(),
            provider.getDisplayName(),
            provider.isEnabled(),
            provider.isDefaultProvider(),
            provider.getSortOrder(),
            provider.getIssuerUri(),
            provider.getClientId(),
            mappingDto,
            state,
            provider.getCreatedAt(),
            provider.getUpdatedAt())
        .jwkSetUri(provider.getJwkSetUri())
        .registryMessage(provider.isEnabled() ? health.message() : null);
  }

  static OidcProviderDraft toDraft(OidcProviderRequest request) {
    OidcClaimMappingDto mapping = request.getClaimMapping();
    OidcClaimMapping domainMapping =
        mapping == null
            ? OidcClaimMapping.keycloakDefaults()
            : new OidcClaimMapping(
                mapping.getEmailClaim(),
                mapping.getDisplayNameClaim(),
                mapping.getRolesClaim(),
                mapping.getSystemAdminRole(),
                mapping.getAuditorRole(),
                mapping.getGroupsClaim());
    return new OidcProviderDraft(
        request.getDisplayName(),
        request.getIssuerUri(),
        request.getClientId(),
        request.getJwkSetUri(),
        domainMapping);
  }
}
