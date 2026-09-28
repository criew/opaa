package io.opaa.revision.web;

import io.opaa.api.dto.AccessAsOfPage;
import io.opaa.api.types.AccessAsOfObjectType;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.revision.PointInTimeAccessService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Stichtagsauskunft (#1822), a subresource of {@code AuditController}. No
 * {@code @PreAuthorize}, and {@code reason} is bound {@code required = false}: the AUDITOR role and
 * the mandatory reason are checked inside {@link PointInTimeAccessService}, so the rejected attempt
 * is itself an entry, which it could not be if a security interceptor or the binding turned it away
 * first.
 */
@RestController
@RequestMapping("/api/v1/audit")
public class PointInTimeAccessController {

  private final PointInTimeAccessService pointInTimeAccessService;

  public PointInTimeAccessController(PointInTimeAccessService pointInTimeAccessService) {
    this.pointInTimeAccessService = pointInTimeAccessService;
  }

  @GetMapping("/access-as-of")
  public AccessAsOfPage listAccessAsOf(
      @RequestParam("objectType") AccessAsOfObjectType objectType,
      @RequestParam("objectId") UUID objectId,
      @RequestParam("from") Instant from,
      @RequestParam("to") Instant to,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "50") int size,
      @RequestParam(name = "reason", required = false) String reason,
      @Caller CurrentUser caller) {
    return PointInTimeAccessResponseMapper.toPage(
        pointInTimeAccessService.readersOf(
            caller.organizationId(),
            caller.id(),
            reason,
            objectType,
            objectId,
            from,
            to,
            page,
            size));
  }
}
