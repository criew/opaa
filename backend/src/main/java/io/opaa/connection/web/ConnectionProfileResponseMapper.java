package io.opaa.connection.web;

import io.opaa.api.dto.ConnectionProfileCreateRequest;
import io.opaa.api.dto.ConnectionProfileImpactResponse;
import io.opaa.api.dto.ConnectionProfileOption;
import io.opaa.api.dto.ConnectionProfileResponse;
import io.opaa.api.dto.ConnectionProfileUpdateRequest;
import io.opaa.common.ValidationException;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileService.ProfileImpact;
import io.opaa.connection.profile.ConnectionProfileValues;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.knowledge.SourceType;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** Maps connection profiles onto their generated responses; the secret has no field in any. */
final class ConnectionProfileResponseMapper {

  private ConnectionProfileResponseMapper() {}

  static ConnectionProfileValues toValues(ConnectionProfileCreateRequest request) {
    return new ConnectionProfileValues(
        request.getName(),
        request.getServerUrl(),
        request.getAuthMethod(),
        request.getOwnership(),
        request.getClientId(),
        toDate(request.getClientSecretExpiresOn()),
        request.getTenant(),
        request.getScopes(),
        toSettings(request.getConnectorSettings()));
  }

  static ConnectionProfileValues toValues(ConnectionProfileUpdateRequest request) {
    return new ConnectionProfileValues(
        request.getName(),
        request.getServerUrl(),
        request.getAuthMethod(),
        request.getOwnership(),
        request.getClientId(),
        toDate(request.getClientSecretExpiresOn()),
        request.getTenant(),
        request.getScopes(),
        toSettings(request.getConnectorSettings()));
  }

  static SourceType toSourceType(String key) {
    if (!SourceType.isKey(key)) {
      throw new ValidationException(
          "sourceType " + key + " ist kein gültiger Quellentyp-Schlüssel");
    }
    return SourceType.of(key);
  }

  static ConnectionProfileResponse toResponse(
      ConnectionProfile profile, boolean secretExpiresSoon, long connectionCount) {
    ConnectorData settings = ConnectorData.fromJson(profile.getConnectorSettings());
    return new ConnectionProfileResponse()
        .id(profile.getId())
        .name(profile.getName())
        .sourceType(profile.getSourceType().key())
        .serverUrl(profile.getServerUrl())
        .authMethod(profile.getAuthMethod())
        .ownership(profile.getOwnership())
        .clientId(profile.getClientId())
        .clientSecretSet(profile.isClientSecretSet())
        .clientSecretExpiresOn(
            profile.getClientSecretExpiresOn() == null
                ? null
                : profile.getClientSecretExpiresOn().toString())
        .clientSecretExpiresSoon(secretExpiresSoon)
        .tenant(profile.getTenant())
        .scopes(profile.getScopes())
        .connectorSettings(settings == null ? null : settings.asMap())
        .connectionCount(connectionCount)
        .createdAt(profile.getCreatedAt())
        .updatedAt(profile.getUpdatedAt());
  }

  static ConnectionProfileOption toOption(ConnectionProfile profile) {
    return new ConnectionProfileOption()
        .id(profile.getId())
        .name(profile.getName())
        .sourceType(profile.getSourceType().key())
        .serverUrl(profile.getServerUrl())
        .authMethod(profile.getAuthMethod());
  }

  static ConnectionProfileImpactResponse toResponse(ProfileImpact impact) {
    return new ConnectionProfileImpactResponse()
        .connections(impact.connections())
        .libraries(impact.libraries());
  }

  private static LocalDate toDate(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      throw new ValidationException("clientSecretExpiresOn ist kein gültiges Datum");
    }
  }

  private static ConnectorData toSettings(Map<String, Object> settings) {
    return settings == null ? null : ConnectorData.of(settings);
  }
}
