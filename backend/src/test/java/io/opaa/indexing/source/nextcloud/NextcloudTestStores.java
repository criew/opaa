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
    return open(settings, NextcloudProperties.DEFAULT_MAX_RESPONSE_BYTES);
  }

  /** {@link #open(SourceSettings)} with its own bound on one {@code PROPFIND} answer. */
  public static FileStore open(SourceSettings settings, long maxResponseBytes) {
    return open(settings, maxResponseBytes, RequestBudget.unbounded());
  }

  /** {@link #open(SourceSettings)} whose requests count against {@code budget}. */
  public static FileStore open(SourceSettings settings, RequestBudget budget) {
    return open(settings, NextcloudProperties.DEFAULT_MAX_RESPONSE_BYTES, budget);
  }

  private static FileStore open(
      SourceSettings settings, long maxResponseBytes, RequestBudget budget) {
    NextcloudConnection connection = NextcloudConnection.of(settings, settings.sourceCredentials());
    NextcloudDav dav =
        new NextcloudDav(
            connection,
            TargetAddressValidator.disabled(),
            SourceRequestPolicy.defaults(),
            budget,
            Duration.ofSeconds(30),
            maxResponseBytes);
    return new NextcloudFileStore(dav, NextcloudSourceSettings.read(settings.connectorSettings()));
  }
}
