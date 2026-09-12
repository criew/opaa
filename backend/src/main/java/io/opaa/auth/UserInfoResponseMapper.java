package io.opaa.auth;

import io.opaa.api.dto.UserInfoResponse;

/**
 * Maps the caller's snapshot onto the generated {@link UserInfoResponse} (ADR-0006: API DTOs are
 * generated from the specification, never hand-written). {@code createdReason} is null for every
 * account that is not a local one - an identity provider's account has no creation reason.
 */
final class UserInfoResponseMapper {

  private UserInfoResponseMapper() {}

  static UserInfoResponse toResponse(CurrentUser caller, String createdReason) {
    UserInfoResponse response =
        new UserInfoResponse(
            caller.id(), caller.email(), caller.displayName(), caller.systemRole().name());
    response.setCreatedReason(createdReason);
    return response;
  }
}
