package io.opaa.connection.web;

import io.opaa.api.dto.ConnectionLogPage;
import io.opaa.api.types.ConnectionLogEventType;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.connection.log.ConnectionLogQueryService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading the connection log. No {@code @PreAuthorize}: the role check sits in {@link
 * ConnectionLogQueryService} so that a rejected attempt is logged too, and {@code reason} is bound
 * optional so that a missing one reaches it as well.
 */
@RestController
public class ConnectionLogController {

  private final ConnectionLogQueryService queryService;

  public ConnectionLogController(ConnectionLogQueryService queryService) {
    this.queryService = queryService;
  }

  @GetMapping("/api/v1/audit/connection-log")
  public ConnectionLogPage listConnectionLog(
      @RequestParam("from") Instant from,
      @RequestParam("to") Instant to,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "50") int size,
      @RequestParam(name = "reason", required = false) String reason,
      @RequestParam(name = "eventType", required = false) ConnectionLogEventType eventType,
      @RequestParam(name = "profileId", required = false) UUID profileId,
      @Caller CurrentUser caller) {
    return ConnectionLogResponseMapper.toPage(
        queryService.find(
            caller.organizationId(),
            caller.id(),
            reason,
            new ConnectionLogQueryService.Query(from, to, eventType, profileId, page, size)));
  }
}
