package io.opaa.api;

import io.opaa.api.dto.LibraryDiagnosticsLockRequest;
import io.opaa.api.dto.LibraryDiagnosticsLockResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.diagnosticaccess.LibraryDiagnosticsLockService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sets and lifts a library's Diagnosesperre. Kept out of {@code LibraryController}: the
 * authorization rule here is different from every other library endpoint - it deliberately does not
 * grant a system admin the usual floor, see {@link LibraryDiagnosticsLockService}.
 */
@RestController
public class LibraryDiagnosticsLockController {

  private final LibraryDiagnosticsLockService lockService;

  public LibraryDiagnosticsLockController(LibraryDiagnosticsLockService lockService) {
    this.lockService = lockService;
  }

  @PutMapping("/api/v1/libraries/{libraryId}/diagnostics-lock")
  public LibraryDiagnosticsLockResponse setLibraryDiagnosticsLock(
      @PathVariable UUID libraryId,
      @Valid @RequestBody LibraryDiagnosticsLockRequest request,
      @Caller CurrentUser caller) {
    return DiagnosticAccessResponseMapper.toResponse(
        lockService.setLocked(caller, libraryId, request.getLocked()));
  }
}
