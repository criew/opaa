package io.opaa.connection.web;

import io.opaa.api.dto.McpServerRequest;
import io.opaa.api.dto.McpServerResponse;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.McpServerProfileService;
import io.opaa.connection.profile.PersonCount;

/** Maps MCP server profiles to their answers; no client secret, no token. */
final class McpServerResponseMapper {

  private McpServerResponseMapper() {}

  static McpServerProfileService.Values toValues(McpServerRequest request) {
    return new McpServerProfileService.Values(
        request.getName(),
        request.getServerUrl(),
        request.getClientId(),
        request.getScopes(),
        request.getResponsibleGroupId());
  }

  static McpServerResponse toResponse(ConnectionProfile profile, PersonCount connectedAccounts) {
    return new McpServerResponse()
        .id(profile.getId())
        .name(profile.getName())
        .serverUrl(profile.getServerUrl())
        .authMethod(profile.getAuthMethod())
        .clientId(profile.getClientId())
        .clientSecretSet(profile.isClientSecretSet())
        .scopes(profile.getScopes())
        .responsibleGroupId(profile.getResponsibleGroupId())
        .issuer(profile.getIssuer())
        .authorizationEndpoint(profile.getEndpoints().authorization())
        .tokenEndpoint(profile.getEndpoints().token())
        .revocationEndpoint(profile.getEndpoints().revocation())
        .locked(profile.isLocked())
        .lockedAt(profile.getLockedAt())
        .connectedAccounts(ConnectionProfileResponseMapper.toCount(connectedAccounts))
        .createdAt(profile.getCreatedAt())
        .updatedAt(profile.getUpdatedAt());
  }
}
