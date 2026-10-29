package io.opaa.library.web;

import io.opaa.api.dto.SharedLibraryQuotaResponse;
import io.opaa.library.SharedLibraryQuotaAdministration.QuotaSetting;

/** Maps the storage quota per shared library onto the API. */
final class SharedLibraryQuotaResponseMapper {

  private SharedLibraryQuotaResponseMapper() {}

  static SharedLibraryQuotaResponse toResponse(QuotaSetting setting) {
    return new SharedLibraryQuotaResponse(
            setting.quotaBytes(), setting.capBytes(), setting.librariesOverQuota())
        .ownQuotaBytes(setting.ownQuotaBytes());
  }
}
