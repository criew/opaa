package io.opaa.indexing.source.googledrive;

import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.ProxyAndCredentials;
import io.opaa.sourceaccess.Sleeper;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import java.net.URI;
import java.util.function.Supplier;

/**
 * Opens a {@link DriveApi} on the resolved settings of a library: {@code sourceUrl} as the only
 * target of the token, the library's proxy, certificate validation always on.
 */
final class DriveApiFactory {

  private final GoogleDriveProperties properties;
  private final TargetAddressValidator targetAddressValidator;
  private final Sleeper sleeper;

  DriveApiFactory(
      GoogleDriveProperties properties,
      TargetAddressValidator targetAddressValidator,
      Sleeper sleeper) {
    this.properties = properties;
    this.targetAddressValidator = targetAddressValidator;
    this.sleeper = sleeper;
  }

  GoogleDriveProperties properties() {
    return properties;
  }

  /**
   * @throws ProxyAndCredentials.InvalidProxyConfigurationException for an unreadable proxy
   */
  DriveApi open(SourceSettings settings, Supplier<String> token, RequestBudget budget) {
    ProxyAndCredentials proxy = ProxyAndCredentials.parse(settings.sourceProxy(), null);
    return new DriveApi(
        URI.create(settings.sourceUrl()),
        token,
        SourceHttpClientFactory.buildHttpClient(proxy.proxyHost(), proxy.proxyPort(), false),
        targetAddressValidator,
        properties.requestTimeout(),
        budget,
        properties.maxRetries(),
        properties.retryBackoff(),
        sleeper);
  }
}
