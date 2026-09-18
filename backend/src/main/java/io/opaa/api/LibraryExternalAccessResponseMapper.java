package io.opaa.api;

import io.opaa.api.dto.ExternalAccessLibraryResponse;
import io.opaa.api.dto.LibraryExternalAccessResponse;
import io.opaa.library.ExternalAccessLibrary;
import io.opaa.library.LibraryExternalAccess;
import java.util.List;

/**
 * Maps {@link LibraryExternalAccess}/{@link ExternalAccessLibrary} onto their generated response
 * counterparts (ADR-0006: API DTOs are generated from the specification, never hand-written).
 */
final class LibraryExternalAccessResponseMapper {

  private LibraryExternalAccessResponseMapper() {}

  static LibraryExternalAccessResponse toResponse(LibraryExternalAccess access) {
    return new LibraryExternalAccessResponse(
            access.libraryId(), access.state(), access.tokenCount(), access.maxReleaseDays())
        .expiresAt(access.expiresAt())
        .setAt(access.setAt())
        .setByDisplayName(access.setByDisplayName());
  }

  static ExternalAccessLibraryResponse toListResponse(ExternalAccessLibrary entry) {
    return new ExternalAccessLibraryResponse(
        entry.library().getId(), entry.library().getName(), toResponse(entry.access()));
  }

  static List<ExternalAccessLibraryResponse> toListResponses(List<ExternalAccessLibrary> entries) {
    return entries.stream().map(LibraryExternalAccessResponseMapper::toListResponse).toList();
  }
}
