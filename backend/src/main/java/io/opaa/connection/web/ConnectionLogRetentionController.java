package io.opaa.connection.web;

import io.opaa.api.dto.ConnectionLogRetentionRequest;
import io.opaa.api.dto.ConnectionLogRetentionResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.connection.log.ConnectionLogRetentionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The connection log's retention period; the system-admin check lives in the service. */
@RestController
public class ConnectionLogRetentionController {

  private static final String PATH = "/api/v1/admin/connection-log/retention";

  private final ConnectionLogRetentionService retentionService;

  public ConnectionLogRetentionController(ConnectionLogRetentionService retentionService) {
    this.retentionService = retentionService;
  }

  @GetMapping(PATH)
  public ConnectionLogRetentionResponse getConnectionLogRetention(@Caller CurrentUser caller) {
    return ConnectionLogResponseMapper.toRetentionResponse(retentionService.read(caller));
  }

  @PutMapping(PATH)
  public ConnectionLogRetentionResponse updateConnectionLogRetention(
      @Valid @RequestBody ConnectionLogRetentionRequest request, @Caller CurrentUser caller) {
    return ConnectionLogResponseMapper.toRetentionResponse(
        retentionService.updateRetentionMonths(caller, request.getRetentionMonths()));
  }
}
