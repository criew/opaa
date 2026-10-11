package io.opaa.indexing.source.dropbox;

import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.RunCredentials;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.ProxyAndCredentials;
import io.opaa.sourceaccess.Sleeper;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Opens a {@link DropboxApi} on the resolved settings of a library: the configured API and content
 * hosts, the profile's proxy, certificate validation always on.
 */
final class DropboxApiFactory {

  private final DropboxProperties properties;
  private final TargetAddressValidator targetAddressValidator;
  private final Sleeper sleeper;

  DropboxApiFactory(
      DropboxProperties properties,
      TargetAddressValidator targetAddressValidator,
      Sleeper sleeper) {
    this.properties = properties;
    this.targetAddressValidator = targetAddressValidator;
    this.sleeper = sleeper;
  }

  DropboxProperties properties() {
    return properties;
  }

  /**
   * @throws ProxyAndCredentials.InvalidProxyConfigurationException for an unreadable proxy
   */
  DropboxApi open(SourceSettings settings, Supplier<String> token, RequestBudget budget) {
    return open(settings, token, null, budget);
  }

  /**
   * A run's API: the token asked from {@code credentials} per request, and once more after Dropbox
   * rejected it.
   *
   * @throws ProxyAndCredentials.InvalidProxyConfigurationException for an unreadable proxy
   */
  DropboxApi open(SourceSettings settings, RunCredentials credentials, RequestBudget budget) {
    return open(settings, credentials::value, credentials::renewedAfterRejection, budget);
  }

  private DropboxApi open(
      SourceSettings settings,
      Supplier<String> token,
      Predicate<String> renewedAfterRejection,
      RequestBudget budget) {
    ProxyAndCredentials proxy = ProxyAndCredentials.parse(settings.sourceProxy(), null);
    return new DropboxApi(
        properties.apiBase(),
        properties.contentBase(),
        token,
        renewedAfterRejection,
        SourceHttpClientFactory.buildHttpClient(proxy.proxyHost(), proxy.proxyPort(), false),
        targetAddressValidator,
        properties.requestTimeout(),
        properties.downloadTimeout(),
        budget,
        properties.maxRetries(),
        properties.maxRetryWait(),
        sleeper);
  }
}
