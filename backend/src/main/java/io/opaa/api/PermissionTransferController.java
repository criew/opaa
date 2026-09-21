package io.opaa.api;

import io.opaa.api.dto.PermissionTransferPreviewRequest;
import io.opaa.api.dto.PermissionTransferPreviewResponse;
import io.opaa.api.dto.PermissionTransferRequest;
import io.opaa.api.dto.PermissionTransferResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.permission.PermissionTransferOrder;
import io.opaa.permission.PermissionTransferService;
import jakarta.validation.Valid;
import java.util.LinkedHashSet;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one transfer of rights from one subject to another (#1834, ADR-0036 Entscheidung 10) - four
 * occasions, one mechanic: reorganisation, provider replacement, change of group mechanism and
 * succession.
 *
 * <p>No {@code @PreAuthorize} here on purpose: who may do what depends on the source and the scope,
 * not on the role alone - the system administration transfers within its organization, everybody
 * else hands over the ownership and the responsibility they carry themselves. {@code
 * PermissionTransferService} decides both, so the rule lives in one place instead of two.
 */
@RestController
@RequestMapping("/api/v1/permission-transfers")
public class PermissionTransferController {

  private final PermissionTransferService transferService;

  public PermissionTransferController(PermissionTransferService transferService) {
    this.transferService = transferService;
  }

  @PostMapping("/preview")
  public PermissionTransferPreviewResponse preview(
      @Valid @RequestBody PermissionTransferPreviewRequest request, @Caller CurrentUser caller) {
    PermissionTransferOrder order =
        new PermissionTransferOrder(
            request.getSourceType(),
            request.getSourceId(),
            request.getTargetType(),
            request.getTargetId(),
            new LinkedHashSet<>(PermissionTransferResponseMapper.toScope(request.getScope())));
    return PermissionTransferResponseMapper.toResponse(transferService.preview(order, caller));
  }

  @PostMapping
  public ResponseEntity<PermissionTransferResponse> transfer(
      @Valid @RequestBody PermissionTransferRequest request, @Caller CurrentUser caller) {
    PermissionTransferOrder order =
        new PermissionTransferOrder(
            request.getSourceType(),
            request.getSourceId(),
            request.getTargetType(),
            request.getTargetId(),
            new LinkedHashSet<>(PermissionTransferResponseMapper.toScope(request.getScope())));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            PermissionTransferResponseMapper.toResponse(
                transferService.transfer(
                    order,
                    Boolean.TRUE.equals(request.getConfirmed()),
                    request.getPreviewId(),
                    caller)));
  }
}
