package io.opaa.connection.web;

import io.opaa.api.dto.ConnectionProfileRef;
import io.opaa.api.dto.ConnectionProfileRequestPageResponse;
import io.opaa.api.dto.ConnectionProfileRequestResponse;
import io.opaa.api.dto.ConnectionProfileRequestState;
import io.opaa.common.ValidationException;
import io.opaa.connection.request.ConnectionProfileRequest;
import io.opaa.connection.request.ConnectionProfileRequestService.RequestPage;
import io.opaa.connection.request.ConnectionProfileRequestService.RequestView;
import io.opaa.connection.request.ProfileRequestState;

/** Maps connection profile requests onto their generated responses. */
final class ConnectionProfileRequestResponseMapper {

  private ConnectionProfileRequestResponseMapper() {}

  static ConnectionProfileRequestResponse toResponse(RequestView view) {
    ConnectionProfileRequest request = view.request();
    return new ConnectionProfileRequestResponse()
        .id(request.getId())
        .sourceType(request.getSourceType().key())
        .serverUrl(request.getServerUrl())
        .reason(request.getReason())
        .state(ConnectionProfileRequestState.valueOf(request.getState().name()))
        .requestedByName(view.requestedByName())
        .createdAt(request.getCreatedAt())
        .resolvedAt(request.getResolvedAt())
        .profile(
            view.profileName() == null
                ? null
                : new ConnectionProfileRef().id(request.getProfileId()).name(view.profileName()))
        .answer(request.getAnswer());
  }

  static ConnectionProfileRequestPageResponse toResponse(RequestPage page) {
    return new ConnectionProfileRequestPageResponse()
        .items(
            page.items().stream().map(ConnectionProfileRequestResponseMapper::toResponse).toList())
        .total(page.total())
        .page(page.page())
        .size(page.size());
  }

  static ProfileRequestState toState(ConnectionProfileRequestState state) {
    return state == null ? null : ProfileRequestState.valueOf(state.name());
  }

  /** The state of a query parameter; absent for every state, anything unknown a 400. */
  static ProfileRequestState toState(String state) {
    if (state == null || state.isBlank()) {
      return null;
    }
    try {
      return ProfileRequestState.valueOf(state);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("state " + state + " ist kein gültiger Zustand");
    }
  }
}
