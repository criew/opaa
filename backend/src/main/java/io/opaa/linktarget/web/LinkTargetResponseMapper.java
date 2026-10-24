package io.opaa.linktarget.web;

import io.opaa.api.dto.LinkTargets;
import io.opaa.linktarget.LinkTargetSettings;

/** Maps the allowed link targets to their API response. */
final class LinkTargetResponseMapper {

  private LinkTargetResponseMapper() {}

  static LinkTargets toResponse(LinkTargetSettings settings) {
    return new LinkTargets(settings.allowedOrigins(), settings.updatedAt());
  }
}
