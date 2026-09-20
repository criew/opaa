package io.opaa.api;

import io.opaa.api.dto.PermissionHistoryRetentionRequest;
import io.opaa.api.dto.PermissionHistoryRetentionResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.permission.PermissionHistoryRetentionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The governance setting "Aufbewahrungshöchstdauer der Rechtehistorie" (ADR-0036, Entscheidung 8),
 * next to the retention of the protocol and of the diagnostic context protocol.
 *
 * <p>Deliberately no {@code @PreAuthorize}: the system-admin check lives in the service, so one
 * place decides it for every caller and a second call site cannot bypass it by not being a
 * controller method.
 */
@RestController
@RequestMapping("/api/v1/admin/permission-history")
public class PermissionHistoryRetentionController {

  private final PermissionHistoryRetentionService retentionService;

  public PermissionHistoryRetentionController(PermissionHistoryRetentionService retentionService) {
    this.retentionService = retentionService;
  }

  @GetMapping("/retention")
  public PermissionHistoryRetentionResponse getPermissionHistoryRetention(
      @Caller CurrentUser caller) {
    return PermissionHistoryRetentionResponseMapper.toResponse(retentionService.read(caller));
  }

  @PutMapping("/retention")
  public PermissionHistoryRetentionResponse updatePermissionHistoryRetention(
      @Valid @RequestBody PermissionHistoryRetentionRequest request, @Caller CurrentUser caller) {
    return PermissionHistoryRetentionResponseMapper.toResponse(
        retentionService.updateRetentionMonths(caller, request.getRetentionMonths()));
  }
}
