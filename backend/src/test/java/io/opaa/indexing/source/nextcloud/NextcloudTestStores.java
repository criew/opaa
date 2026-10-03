package io.opaa.indexing.source.nextcloud;

import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Opens the run-side store against a Nextcloud the way the executor does, for tests. */
public final class NextcloudTestStores {

  private NextcloudTestStores() {}

  /** The source settings of a library on {@code baseUrl} over {@code folders}. */
  public static SourceSettings settings(String baseUrl, String credentials, List<String> folders) {
    return new SourceSettings(
        null, baseUrl, null, credentials, false, ConnectorData.of(Map.of("folders", folders)));
  }

  /** Moves the ids a fixture keeps by path along with a renamed file or folder {@code from}. */
  public static <V> void renameIds(Map<String, V> ids, String from, String to) {
    Map<String, V> moved = new java.util.HashMap<>();
    ids.keySet()
        .removeIf(
            path -> {
              if (path.equals(from) || path.startsWith(from + "/")) {
                moved.put(to + path.substring(from.length()), ids.get(path));
                return true;
              }
              return false;
            });
    ids.putAll(moved);
  }

  /** A run's store with unbounded budget and the default bounds. */
  public static FileStore open(SourceSettings settings) {
    NextcloudConnection connection = NextcloudConnection.of(settings, settings.sourceCredentials());
    NextcloudDav dav =
        new NextcloudDav(
            connection,
            TargetAddressValidator.disabled(),
            SourceRequestPolicy.defaults(),
            RequestBudget.unbounded(),
            Duration.ofSeconds(30),
            NextcloudProperties.DEFAULT_MAX_RESPONSE_BYTES);
    return new NextcloudFileStore(dav, NextcloudSourceSettings.read(settings.connectorSettings()));
  }
}
