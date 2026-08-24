package io.opaa.api;

import io.opaa.api.dto.AuditEventPage;
import io.opaa.api.dto.AuditEventResponse;
import io.opaa.api.dto.AuditIncidentScopeRequest;
import io.opaa.api.dto.AuditIncidentScopeResponse;
import io.opaa.audit.AuditEventType;
import io.opaa.audit.AuditIncidentScopeGrant;
import io.opaa.audit.AuditIncidentScopeService;
import io.opaa.audit.AuditLogEntry;
import io.opaa.audit.AuditObjectType;
import io.opaa.audit.AuditQueryService;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The #393 revision access path (AUDITOR role only) - exactly the four bounded queries the
 * specification allows plus the one personenbezogene exception, all funnelled through {@link
 * AuditQueryService} so #394's self-logging has a single seam to hook into. Deliberately no generic
 * "search" endpoint and no {@code actor}/{@code sort} request parameter anywhere below - see {@link
 * AuditQueryService}'s own Javadoc for why.
 *
 * <p><b>#394:</b> none of the five read endpoints below declares {@code @PreAuthorize} any more -
 * both the AUDITOR role and the mandatory {@code reason} are enforced inside {@link
 * AuditQueryService} itself, the only place a rejected attempt can also be logged (see that class's
 * Javadoc). {@code reason} is bound {@code required = false} here even though the OpenAPI spec
 * documents it as required - the same deliberate gap, for the same purpose: a request missing it
 * must still reach {@link AuditQueryService} so the rejection itself is logged, rather than being
 * short-circuited by Spring MVC's own "missing parameter" 400.
 *
 * <p><b>Deliberate, narrower scope boundary (PR #450 review, finding 3):</b> {@code reason} is the
 * only parameter given this treatment. {@code from}/{@code to}/{@code objectType}/{@code
 * eventType}/{@code page}/{@code size} are still bound as their real types with Spring MVC's normal
 * {@code required = true} default (or {@code int}, which cannot even represent "absent") - a
 * missing or unparsable value there (e.g. {@code ?objectType=NOT_A_REAL_TYPE} or {@code
 * ?from=gestern}) is rejected by Spring MVC's argument resolution before this controller's method
 * body, and therefore before {@link AuditQueryService}, ever runs; {@code
 * GlobalExceptionHandler#handleMissingServletRequestParameterException}/{@code
 * #handleMethodArgumentTypeMismatchException} turn that into the same 400 an AUDITOR would see for
 * a well-formed but business-invalid request, but - unlike every rejection {@link
 * AuditQueryService#loggedAccess} handles - <b>no {@code audit_log} entry is written for it.</b>
 * "Der Zugriff auf Protokolldaten erzeugt selbst einen Eintrag" is therefore accurate for every
 * request that reaches the funnel, not for every HTTP request against these five paths; extending
 * self-logging to cover binding failures too would mean binding every one of these parameters as
 * {@code String} and parsing/validating them inside {@link AuditQueryService} instead of relying on
 * Spring MVC's argument resolution - a larger, separately reviewable change, tracked as a follow-up
 * rather than folded into this one.
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

  private final AuditQueryService queryService;
  private final AuditIncidentScopeService incidentScopeService;

  public AuditController(
      AuditQueryService queryService, AuditIncidentScopeService incidentScopeService) {
    this.queryService = queryService;
    this.incidentScopeService = incidentScopeService;
  }

  // #394: deliberately no @PreAuthorize on any of the five read endpoints below - the AUDITOR
  // role check (and the mandatory reason check) happens inside AuditQueryService itself now, so a
  // denial can be logged there; see that class's Javadoc for why an annotation-only check would
  // make a role-based 403 invisible to the one class the specification requires to log it.
  @GetMapping("/events/by-object")
  public AuditEventPage listAuditEventsByObject(
      @RequestParam("objectType") AuditObjectType objectType,
      @RequestParam("objectId") String objectId,
      @RequestParam("from") Instant from,
      @RequestParam("to") Instant to,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "50") int size,
      // required=false (rather than the OpenAPI spec's own "required: true") so a missing reason
      // reaches AuditQueryService, which is where the mandatory-reason rejection is itself logged
      // (#394) rather than short-circuited by Spring MVC's own binding error.
      @RequestParam(name = "reason", required = false) String reason,
      @Caller CurrentUser caller) {
    Page<AuditLogEntry> result =
        queryService.byObject(
            caller.organizationId(),
            caller.id(),
            reason,
            objectType,
            objectId,
            from,
            to,
            page,
            size);
    return toPage(result);
  }

  @GetMapping("/events/by-time-range")
  public AuditEventPage listAuditEventsByTimeRange(
      @RequestParam("from") Instant from,
      @RequestParam("to") Instant to,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "50") int size,
      @RequestParam(name = "reason", required = false) String reason,
      @Caller CurrentUser caller) {
    Page<AuditLogEntry> result =
        queryService.byTimeRange(
            caller.organizationId(), caller.id(), reason, from, to, page, size);
    return toPage(result);
  }

  @GetMapping("/events/by-event-type")
  public AuditEventPage listAuditEventsByEventType(
      @RequestParam("eventType") AuditEventType eventType,
      @RequestParam("from") Instant from,
      @RequestParam("to") Instant to,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "50") int size,
      @RequestParam(name = "reason", required = false) String reason,
      @Caller CurrentUser caller) {
    Page<AuditLogEntry> result =
        queryService.byEventType(
            caller.organizationId(), caller.id(), reason, eventType, from, to, page, size);
    return toPage(result);
  }

  @GetMapping("/events/by-correlation")
  public AuditEventPage listAuditEventsByCorrelation(
      @RequestParam("correlationRef") String correlationRef,
      @RequestParam("from") Instant from,
      @RequestParam("to") Instant to,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "50") int size,
      @RequestParam(name = "reason", required = false) String reason,
      @Caller CurrentUser caller) {
    Page<AuditLogEntry> result =
        queryService.byCorrelation(
            caller.organizationId(), caller.id(), reason, correlationRef, from, to, page, size);
    return toPage(result);
  }

  @PreAuthorize("hasRole('AUDITOR')")
  @PostMapping("/incident-scopes")
  public ResponseEntity<AuditIncidentScopeResponse> requestAuditIncidentScope(
      @Valid @RequestBody AuditIncidentScopeRequest request, @Caller CurrentUser caller) {
    AuditIncidentScopeGrant grant =
        incidentScopeService.request(
            caller.organizationId(),
            caller.id(),
            request.getSubjectUserId(),
            request.getScopeStart(),
            request.getScopeEnd(),
            request.getPurpose(),
            request.getReason());
    return ResponseEntity.status(HttpStatus.CREATED).body(toIncidentScopeResponse(grant));
  }

  @PreAuthorize("hasRole('AUDITOR')")
  @PostMapping("/incident-scopes/{scopeId}/approve")
  public AuditIncidentScopeResponse approveAuditIncidentScope(
      @PathVariable UUID scopeId, @Caller CurrentUser caller) {
    AuditIncidentScopeGrant grant =
        incidentScopeService.approve(caller.organizationId(), scopeId, caller.id());
    return toIncidentScopeResponse(grant);
  }

  @GetMapping("/incident-scopes/{scopeId}/events")
  public AuditEventPage listAuditEventsByIncidentScope(
      @PathVariable UUID scopeId,
      @RequestParam("from") Instant from,
      @RequestParam("to") Instant to,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "50") int size,
      @RequestParam(name = "reason", required = false) String reason,
      @Caller CurrentUser caller) {
    Page<AuditLogEntry> result =
        queryService.byIncidentScope(
            caller.organizationId(), caller.id(), reason, scopeId, from, to, page, size);
    return toPage(result);
  }

  private AuditEventPage toPage(Page<AuditLogEntry> page) {
    AuditEventPage response =
        new AuditEventPage(
            page.getContent().stream().map(this::toEventResponse).toList(),
            page.getNumber(),
            page.getSize(),
            page.hasNext());
    return response;
  }

  private AuditEventResponse toEventResponse(AuditLogEntry entry) {
    return new AuditEventResponse(
            entry.getEventId(),
            entry.getRecordedAt(),
            entry.getOrganizationId(),
            entry.getActorKind(),
            entry.getActorRef(),
            entry.getEventType(),
            entry.getObjectType(),
            entry.getObjectId(),
            entry.getOutcome())
        .objectLabel(entry.getObjectLabel())
        .subjectKind(entry.getSubjectKind())
        .subjectRef(entry.getSubjectRef())
        .before(entry.getBefore())
        .after(entry.getAfter())
        .reason(entry.getReason())
        .correlationRef(entry.getCorrelationRef());
  }

  private AuditIncidentScopeResponse toIncidentScopeResponse(AuditIncidentScopeGrant grant) {
    return new AuditIncidentScopeResponse(
            grant.getId(),
            grant.getOrganizationId(),
            grant.getSubjectUserId(),
            grant.getScopeStart(),
            grant.getScopeEnd(),
            grant.getPurpose(),
            grant.getReason(),
            grant.getRequestedByUserId(),
            grant.getRequestedAt(),
            grant.getStatus())
        .approvedByUserId(grant.getApprovedByUserId())
        .approvedAt(grant.getApprovedAt())
        .usableUntil(grant.getUsableUntil());
  }
}
