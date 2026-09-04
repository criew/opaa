package io.opaa.api;

import io.opaa.api.dto.DiagnosticContextEventDetailResponse;
import io.opaa.api.dto.DiagnosticContextEventPage;
import io.opaa.api.dto.OwnDiagnosticContextEventPage;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.diagnosticaccess.DiagnosticContextLogQueryService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three read paths into the diagnostic context protocol: the Einsichtsrecht, the
 * Gesamtprotokoll and one already-known entry of it.
 *
 * <p>What is not here matters as much as what is: neither method takes any identity but the
 * caller's own, and neither returns a count or grouping - {@code
 * DiagnosticContextPurposeLimitationTest} fails the build if that changes. {@code reason} is bound
 * {@code required = false} even though the specification declares it required - the same deliberate
 * gap {@code AuditController} makes, so a request missing it reaches the service that records the
 * rejected attempt instead of being short-circuited by Spring MVC's own binding error.
 */
@RestController
public class DiagnosticContextLogController {

  private final DiagnosticContextLogQueryService queryService;

  public DiagnosticContextLogController(DiagnosticContextLogQueryService queryService) {
    this.queryService = queryService;
  }

  @GetMapping("/api/v1/me/diagnostic-context-events")
  public OwnDiagnosticContextEventPage listOwnDiagnosticContextEvents(
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "50") int size,
      @Caller CurrentUser caller) {
    return DiagnosticAccessResponseMapper.toOwnPage(queryService.findOwnEvents(caller, page, size));
  }

  @GetMapping("/api/v1/audit/diagnostic-context-events")
  public DiagnosticContextEventPage listDiagnosticContextEvents(
      @RequestParam("from") Instant from,
      @RequestParam("to") Instant to,
      @RequestParam(name = "reason", required = false) String reason,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "50") int size,
      @Caller CurrentUser caller) {
    return DiagnosticAccessResponseMapper.toPage(
        queryService.findByTimeRange(caller, from, to, reason, page, size));
  }

  @GetMapping("/api/v1/audit/diagnostic-context-events/{eventId}")
  public DiagnosticContextEventDetailResponse getDiagnosticContextEvent(
      @PathVariable UUID eventId,
      @RequestParam(name = "reason", required = false) String reason,
      @Caller CurrentUser caller) {
    return DiagnosticAccessResponseMapper.toDetailResponse(
        queryService.findSingleEvent(caller, eventId, reason));
  }
}
