package io.opaa.connection.web;

import io.opaa.api.dto.ConnectionProfileChangeRejection;
import io.opaa.api.dto.ConnectionProfileCreateRequest;
import io.opaa.api.dto.ConnectionProfileImpactResponse;
import io.opaa.api.dto.ConnectionProfileOption;
import io.opaa.api.dto.ConnectionProfileResponse;
import io.opaa.api.dto.ConnectionProfileSignInTestResponse;
import io.opaa.api.dto.ConnectionProfileUpdateRequest;
import io.opaa.api.dto.ConnectorProfileRequirementResponse;
import io.opaa.api.dto.ConnectorTypeStateResponse;
import io.opaa.api.dto.OwnAddressLibrary;
import io.opaa.api.dto.PersonCount;
import io.opaa.api.dto.SourceChangeRejectionCategory;
import io.opaa.common.ValidationException;
import io.opaa.connection.ConnectorReleaseService.ProfileOption;
import io.opaa.connection.oauth.ProfileSignIn.SignInTest;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileService.ProfileImpact;
import io.opaa.connection.profile.ConnectionProfileValues;
import io.opaa.connection.profile.ConnectorLockService.TypeState;
import io.opaa.connection.profile.OwnAddressStock;
import io.opaa.connection.profile.PersonNumbers.ProfileCounts;
import io.opaa.connection.profile.ProfileEndpoints;
import io.opaa.connection.profile.ProfileRequirementService.Overview;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.knowledge.SourceType;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** Maps connection profiles onto their generated responses; the secret has no field in any. */
public final class ConnectionProfileResponseMapper {

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
        toSettings(request.getConnectorSettings()),
        request.getSourceProxy(),
        Boolean.TRUE.equals(request.getSourceInsecureSsl()),
        new ProfileEndpoints(
            request.getAuthorizationEndpoint(),
            request.getTokenEndpoint(),
            request.getRevocationEndpoint()));
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
        toSettings(request.getConnectorSettings()),
        request.getSourceProxy(),
        Boolean.TRUE.equals(request.getSourceInsecureSsl()),
        new ProfileEndpoints(
            request.getAuthorizationEndpoint(),
            request.getTokenEndpoint(),
            request.getRevocationEndpoint()));
  }

  static SourceType toSourceType(String key) {
    if (!SourceType.isKey(key)) {
      throw new ValidationException(
          "sourceType " + key + " ist kein gültiger Quellentyp-Schlüssel");
    }
    return SourceType.of(key);
  }

  static ConnectionProfileResponse toResponse(
      ConnectionProfile profile,
      boolean secretExpiresSoon,
      long connectionCount,
      ProfileCounts accounts) {
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
        .signInRejected(profile.isSignInRejected())
        .tenant(profile.getTenant())
        .scopes(profile.getScopes())
        .authorizationEndpoint(profile.getEndpoints().authorization())
        .tokenEndpoint(profile.getEndpoints().token())
        .revocationEndpoint(profile.getEndpoints().revocation())
        .connectorSettings(settings == null ? null : settings.asMap())
        .sourceProxy(profile.getSourceProxy())
        .sourceInsecureSsl(profile.isSourceInsecureSsl())
        .connectionCount(connectionCount)
        .connectedAccountCount(toCount(accounts.total()))
        .expiredConnectionCount(accounts.expired() == null ? null : toCount(accounts.expired()))
        // a profile for libraries only has no persons to warn about; its ownership is public
        .expiredConnectionWarning(
            profile.getOwnership().admitsPersons() && accounts.expiredWarning())
        .locked(profile.isLocked())
        .lockedAt(profile.getLockedAt())
        .createdAt(profile.getCreatedAt())
        .updatedAt(profile.getUpdatedAt());
  }

  public static ConnectionProfileOption toOption(ProfileOption option) {
    ConnectionProfile profile = option.profile();
    ConnectorData defaults = ConnectorData.fromJson(profile.getConnectorSettings());
    return new ConnectionProfileOption()
        .connectorDefaults(defaults == null ? null : defaults.asMap())
        .creatable(option.creatable())
        .creationNotice(option.notice())
        .id(profile.getId())
        .name(profile.getName())
        .sourceType(profile.getSourceType().key())
        .serverUrl(profile.getServerUrl())
        .authMethod(profile.getAuthMethod())
        .ownership(profile.getOwnership())
        .ownAccount(option.ownAccount())
        .sourceProxy(profile.getSourceProxy())
        .sourceInsecureSsl(profile.isSourceInsecureSsl());
  }

  static ConnectionProfileSignInTestResponse toResponse(SignInTest test) {
    return new ConnectionProfileSignInTestResponse(test.success(), test.message());
  }

  static ConnectorTypeStateResponse toResponse(TypeState state) {
    return new ConnectorTypeStateResponse()
        .sourceType(state.type().key())
        .displayName(state.displayName())
        .locked(state.locked())
        .lockedAt(state.lockedAt())
        .profileSupport(state.profileSupport())
        .profileRequired(state.profileRequired())
        .profileRequiredAt(state.profileRequiredAt())
        .ownAddressStock(
            state.ownAddressStock() == null
                ? null
                : io.opaa.api.dto.OwnAddressStock.valueOf(state.ownAddressStock().name()));
  }

  static ConnectionProfileImpactResponse toResponse(
      ProfileImpact impact, boolean lastForProfileRequirement) {
    return new ConnectionProfileImpactResponse()
        .connections(impact.connections())
        .libraries(impact.libraries())
        .connectedAccounts(toCount(impact.connectedAccounts()))
        .lastForProfileRequirement(lastForProfileRequirement)
        .fullSyncLibraries(impact.fullSyncLibraries())
        .connectionsDiscarded(impact.discards().connections())
        .secretsDiscarded(impact.discards().secrets())
        .configurationsChanged(impact.discards().configurations())
        .connectedAccountsEnded(
            impact.discards().connectedAccounts() == null
                ? null
                : toCount(impact.discards().connectedAccounts()))
        .confirmation(impact.discards().confirmation())
        .rejectedLibraries((long) impact.rejections().size())
        .rejectedPrivateLibraries(
            impact.rejectedPrivateLibraries() == null
                ? null
                : toCount(impact.rejectedPrivateLibraries()))
        .rejections(
            impact.rejections().stream()
                .map(
                    rejection ->
                        new ConnectionProfileChangeRejection()
                            .libraryId(rejection.libraryId())
                            .category(
                                SourceChangeRejectionCategory.valueOf(rejection.category().name()))
                            .message(rejection.message()))
                .toList());
  }

  /** A number of persons' connections, as masked for the administration. */
  static PersonCount toCount(io.opaa.connection.profile.PersonCount count) {
    return new PersonCount().count(count.count()).fewerThan(count.fewerThan());
  }

  static ConnectorProfileRequirementResponse toResponse(Overview overview) {
    return new ConnectorProfileRequirementResponse()
        .state(toResponse(overview.state()))
        .switchable(overview.switchable())
        .notSwitchableReason(overview.notSwitchableReason())
        .ownAddressLibraries(
            overview.ownAddressLibraries().stream()
                .map(
                    library ->
                        new OwnAddressLibrary()
                            .id(library.id())
                            .name(library.name())
                            .ownerType(library.ownerType())
                            .ownerName(library.ownerName()))
                .toList())
        .coverageNotice(overview.coverageGap());
  }

  static OwnAddressStock toStock(io.opaa.api.dto.OwnAddressStock stock) {
    return stock == null ? null : OwnAddressStock.valueOf(stock.name());
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
