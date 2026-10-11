package io.opaa.connection.web;

import io.opaa.api.dto.OutboundProxyRequest;
import io.opaa.api.dto.OutboundProxyResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.connection.OutboundProxyAdministration;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The house-wide outbound proxy; the system-admin check lives in the service. */
@RestController
public class OutboundProxyController {

  private static final String PATH = "/api/v1/admin/outbound-proxy";

  private final OutboundProxyAdministration administration;

  public OutboundProxyController(OutboundProxyAdministration administration) {
    this.administration = administration;
  }

  @GetMapping(PATH)
  public OutboundProxyResponse getOutboundProxy(@Caller CurrentUser caller) {
    return OutboundProxyResponseMapper.toResponse(administration.read(caller));
  }

  @PutMapping(PATH)
  public OutboundProxyResponse updateOutboundProxy(
      @Valid @RequestBody OutboundProxyRequest request, @Caller CurrentUser caller) {
    return OutboundProxyResponseMapper.toResponse(
        administration.change(caller, request.getProxyUrl(), request.getNoProxy()));
  }
}
