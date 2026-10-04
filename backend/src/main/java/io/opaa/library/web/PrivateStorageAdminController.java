package io.opaa.library.web;

import io.opaa.api.dto.PrivateStorageQuotaRequest;
import io.opaa.api.dto.PrivateStorageQuotaResponse;
import io.opaa.api.dto.PrivateStorageSummaryResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.library.PrivateStorageAdministration;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The house-wide storage quota across private libraries and their masked sums, for the system
 * administration; the check of the role lives in {@link PrivateStorageAdministration}.
 */
@RestController
@RequestMapping("/api/v1/admin/private-libraries")
public class PrivateStorageAdminController {

  private final PrivateStorageAdministration administration;

  public PrivateStorageAdminController(PrivateStorageAdministration administration) {
    this.administration = administration;
  }

  @GetMapping("/quota")
  public PrivateStorageQuotaResponse getPrivateStorageQuota(@Caller CurrentUser caller) {
    return PrivateStorageResponseMapper.toResponse(administration.read(caller));
  }

  @PutMapping("/quota")
  public PrivateStorageQuotaResponse updatePrivateStorageQuota(
      @Valid @RequestBody PrivateStorageQuotaRequest request, @Caller CurrentUser caller) {
    return PrivateStorageResponseMapper.toResponse(
        administration.change(caller, request.getQuotaBytes()));
  }

  @GetMapping("/summary")
  public PrivateStorageSummaryResponse getPrivateStorageSummary(@Caller CurrentUser caller) {
    return PrivateStorageResponseMapper.toResponse(administration.summary(caller));
  }
}
