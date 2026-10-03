package io.opaa.indexing.source.smb;

import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.security.TargetAddressValidator;
import java.time.Duration;

/** Opens the run-side store against a share the way the executor does, for tests. */
final class SmbTestStores {

  private SmbTestStores() {}

  static SmbFileStore open(SourceSettings settings, int pageSize) {
    return open(settings, pageSize, RequestBudget.unbounded());
  }

  static SmbFileStore open(SourceSettings settings, int pageSize, RequestBudget budget) {
    SmbAddress address = SmbAddress.parse(settings.sourceUrl());
    SmbShareClient smb =
        SmbShareClient.of(
            address,
            SmbCredentials.parse(settings.sourceCredentials()),
            TargetAddressValidator.disabled(),
            budget,
            Duration.ofSeconds(30));
    return new SmbFileStore(
        smb, address, SmbSourceSettings.read(settings.connectorSettings()), pageSize);
  }
}
