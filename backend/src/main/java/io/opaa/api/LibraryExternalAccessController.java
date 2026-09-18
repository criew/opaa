package io.opaa.api;

import io.opaa.api.dto.ExternalAccessLibraryResponse;
import io.opaa.api.dto.LibraryExternalAccessRequest;
import io.opaa.api.dto.LibraryExternalAccessResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.library.LibraryExternalAccessService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Freigabe einer Bibliothek für Fremdzugänge (#1731) - setting it at the library, and the
 * administration's Bestandsliste of the released ones. Separate from {@code LibraryController}
 * because the release is one decision with its own bar and its own audit trail, not one field among
 * the library's editable details.
 */
@RestController
class LibraryExternalAccessController {

  private final LibraryExternalAccessService externalAccessService;

  LibraryExternalAccessController(LibraryExternalAccessService externalAccessService) {
    this.externalAccessService = externalAccessService;
  }

  @PutMapping("/api/v1/libraries/{libraryId}/external-access")
  LibraryExternalAccessResponse setLibraryExternalAccess(
      @PathVariable UUID libraryId,
      @Valid @RequestBody LibraryExternalAccessRequest request,
      @Caller CurrentUser caller) {
    return LibraryExternalAccessResponseMapper.toResponse(
        externalAccessService.setExternalAccess(
            caller, libraryId, Boolean.TRUE.equals(request.getEnabled()), request.getExpiresAt()));
  }

  @GetMapping("/api/v1/admin/external-access/libraries")
  List<ExternalAccessLibraryResponse> listExternalAccessLibraries(@Caller CurrentUser caller) {
    return LibraryExternalAccessResponseMapper.toListResponses(
        externalAccessService.listReleasedLibraries(caller));
  }
}
