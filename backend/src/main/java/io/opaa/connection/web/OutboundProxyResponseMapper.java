package io.opaa.connection.web;

import io.opaa.api.dto.OutboundProxyResponse;
import io.opaa.outbound.OutboundProxy;

/** Maps the stored outbound proxy onto the API. */
final class OutboundProxyResponseMapper {

  private OutboundProxyResponseMapper() {}

  static OutboundProxyResponse toResponse(OutboundProxy.Settings settings) {
    return new OutboundProxyResponse(settings.noProxy())
        .proxyUrl(settings.proxy() == null ? null : settings.proxy().toUrl())
        .updatedAt(settings.updatedAt());
  }
}
