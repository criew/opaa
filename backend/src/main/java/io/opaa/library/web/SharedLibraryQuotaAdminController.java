package io.opaa.library.web;

import io.opaa.api.dto.SharedLibraryQuotaRequest;
import io.opaa.api.dto.SharedLibraryQuotaResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.library.SharedLibraryQuotaAdministration;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The house-wide storage quota per shared library, for the system administration; the check of the
 * role lives in {@link SharedLibraryQuotaAdministration}, which the settings import shares.
 */
@RestController
@RequestMapping("/api/v1/admin/shared-libraries/quota")
public class SharedLibraryQuotaAdminController {

  private final SharedLibraryQuotaAdministration administration;

  public SharedLibraryQuotaAdminController(SharedLibraryQuotaAdministration administration) {
    this.administration = administration;
  }

  @GetMapping
  public SharedLibraryQuotaResponse getSharedLibraryQuota(@Caller CurrentUser caller) {
    return SharedLibraryQuotaResponseMapper.toResponse(administration.read(caller));
  }

  @PutMapping
  public SharedLibraryQuotaResponse updateSharedLibraryQuota(
      @Valid @RequestBody SharedLibraryQuotaRequest request, @Caller CurrentUser caller) {
    return SharedLibraryQuotaResponseMapper.toResponse(
        administration.change(caller, request.getQuotaBytes()));
  }
}
