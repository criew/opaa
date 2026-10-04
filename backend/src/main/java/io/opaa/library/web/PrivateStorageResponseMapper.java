package io.opaa.library.web;

import io.opaa.api.dto.MaskedNumber;
import io.opaa.api.dto.PrivateStorageProfileSum;
import io.opaa.api.dto.PrivateStorageQuotaResponse;
import io.opaa.api.dto.PrivateStorageRunEnds;
import io.opaa.api.dto.PrivateStorageSummaryResponse;
import io.opaa.library.PrivateStorageAdministration.QuotaSetting;
import io.opaa.library.PrivateStorageSummary;

/** Maps the storage quota of private libraries and its masked sums onto the API. */
final class PrivateStorageResponseMapper {

  private PrivateStorageResponseMapper() {}

  static PrivateStorageQuotaResponse toResponse(QuotaSetting setting) {
    return new PrivateStorageQuotaResponse(
        setting.quotaBytes(), setting.defaultQuotaBytes(), setting.overridden());
  }

  static PrivateStorageSummaryResponse toResponse(PrivateStorageSummary summary) {
    return new PrivateStorageSummaryResponse(
        summary.quotaBytes(),
        toResponse(summary.owners()),
        toResponse(summary.usedBytes()),
        summary.profiles().stream()
            .map(
                profile ->
                    new PrivateStorageProfileSum(
                        profile.profileId(), profile.name(), toResponse(profile.usedBytes())))
            .toList(),
        summary.runWindowDays(),
        summary.runEnds().stream()
            .map(ended -> new PrivateStorageRunEnds(ended.category(), toResponse(ended.runs())))
            .toList());
  }

  private static MaskedNumber toResponse(PrivateStorageSummary.MaskedNumber number) {
    return new MaskedNumber().value(number.value()).fewerThanPersons(number.fewerThanPersons());
  }
}
