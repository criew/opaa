package io.opaa.directory.web;

import io.opaa.api.dto.DirectoryConnectorResponse;
import io.opaa.directory.sync.connector.DirectoryConnectorView;

/**
 * Maps {@link DirectoryConnectorView} onto its generated counterpart (ADR-0006). There is no secret
 * to map: {@link DirectoryConnectorView} has no field for one.
 */
final class DirectoryConnectorResponseMapper {

  private DirectoryConnectorResponseMapper() {}

  static DirectoryConnectorResponse toResponse(DirectoryConnectorView view) {
    if (view == null) {
      return null;
    }
    return new DirectoryConnectorResponse(
        view.type(), view.baseUrl(), view.realm(), view.clientId(), view.updatedAt());
  }
}
