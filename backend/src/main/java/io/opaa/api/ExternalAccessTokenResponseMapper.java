package io.opaa.api;

import io.opaa.api.dto.AdminExternalAccessTokenResponse;
import io.opaa.api.dto.CreatedExternalAccessTokenResponse;
import io.opaa.api.dto.ExternalAccessTokenLibraryResponse;
import io.opaa.api.dto.OwnExternalAccessTokenResponse;
import io.opaa.externalaccess.token.ExternalAccessToken;
import io.opaa.externalaccess.token.ExternalAccessTokenAdminService.ExternalAccessTokenAdminView;
import io.opaa.externalaccess.token.ExternalAccessTokenService.ExternalAccessTokenView;
import io.opaa.externalaccess.token.ExternalAccessTokenService.IssuedExternalAccessToken;
import io.opaa.externalaccess.token.ExternalAccessTokenService.SelectedLibrary;
import java.time.Instant;
import java.util.List;

/**
 * Maps the token domain onto the generated responses (ADR-0006).
 *
 * <p>The asymmetry between the two list shapes is the point, not an omission: {@link #toOwn}
 * carries {@code lastUsedOn}, {@link #toAdmin} does not and has no field for it. Neither carries
 * the token value - that exists only in {@link #toCreated}, once.
 */
final class ExternalAccessTokenResponseMapper {

  private ExternalAccessTokenResponseMapper() {}

  static CreatedExternalAccessTokenResponse toCreated(
      IssuedExternalAccessToken issued, Instant now) {
    ExternalAccessToken token = issued.token();
    return new CreatedExternalAccessTokenResponse(
        token.getId(),
        token.getName(),
        token.getTokenPrefix(),
        issued.rawValue(),
        token.getCreatedAt(),
        token.getExpiresAt(),
        token.status(now),
        toLibraries(issued.view().libraries()));
  }

  static OwnExternalAccessTokenResponse toOwn(ExternalAccessTokenView view, Instant now) {
    ExternalAccessToken token = view.token();
    OwnExternalAccessTokenResponse response =
        new OwnExternalAccessTokenResponse(
            token.getId(),
            token.getName(),
            token.getTokenPrefix(),
            token.getCreatedAt(),
            token.getExpiresAt(),
            token.status(now),
            toLibraries(view.libraries()));
    // ISO date, never a time: what the self view promises is a day, and a timestamp here would be
    // the minute-precise usage history the channel rules out everywhere else.
    response.setLastUsedOn(token.getLastUsedOn() == null ? null : token.getLastUsedOn().toString());
    return response;
  }

  static AdminExternalAccessTokenResponse toAdmin(ExternalAccessTokenAdminView view, Instant now) {
    ExternalAccessToken token = view.token();
    return new AdminExternalAccessTokenResponse(
        token.getId(),
        token.getUserId(),
        view.ownerDisplayName(),
        token.getName(),
        token.getCreatedAt(),
        token.getExpiresAt(),
        token.status(now),
        toLibraries(view.libraries()));
  }

  private static List<ExternalAccessTokenLibraryResponse> toLibraries(
      List<SelectedLibrary> libraries) {
    return libraries.stream()
        .map(
            library ->
                new ExternalAccessTokenLibraryResponse(
                    library.id(), library.name(), library.suspended()))
        .toList();
  }
}
