package io.opaa.indexing.source.sharepoint;

import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.RunCredentials;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.msgraph.GraphClient;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.ProxyAndCredentials;
import io.opaa.sourceaccess.RateLimitHandling;
import io.opaa.sourceaccess.RateLimitPolicy;
import io.opaa.sourceaccess.Sleeper;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Opens a {@link GraphClient} on the resolved settings of a library: {@code sourceUrl} as the only
 * target of the token, the library's proxy, certificate validation always on, every attempt and
 * every throttle wait charged to the {@link RequestBudget}.
 */
final class GraphConnections {

  /** Builds the HTTP client for a proxy, {@code null} host for none. */
  @FunctionalInterface
  interface HttpClients {
    HttpClient build(String proxyHost, int proxyPort);
  }

  private final SharePointProperties properties;
  private final TargetAddressValidator targetAddressValidator;
  private final Sleeper sleeper;
  private final HttpClients httpClients;

  GraphConnections(
      SharePointProperties properties,
      TargetAddressValidator targetAddressValidator,
      Sleeper sleeper) {
    this(
        properties,
        targetAddressValidator,
        sleeper,
        (host, port) -> SourceHttpClientFactory.buildHttpClient(host, port, false));
  }

  GraphConnections(
      SharePointProperties properties,
      TargetAddressValidator targetAddressValidator,
      Sleeper sleeper,
      HttpClients httpClients) {
    this.properties = properties;
    this.targetAddressValidator = targetAddressValidator;
    this.sleeper = sleeper;
    this.httpClients = httpClients;
  }

  SharePointProperties properties() {
    return properties;
  }

  /**
   * A client sending {@code token} as it is, without a renewal after a rejection.
   *
   * @throws ProxyAndCredentials.InvalidProxyConfigurationException for an unreadable proxy
   */
  GraphClient open(SourceSettings settings, Supplier<String> token, RequestBudget budget) {
    return open(settings, token, null, budget);
  }

  /**
   * A run's client: the token asked from {@code credentials} per attempt, and once more after Graph
   * rejected it.
   *
   * @throws ProxyAndCredentials.InvalidProxyConfigurationException for an unreadable proxy
   */
  GraphClient open(SourceSettings settings, RunCredentials credentials, RequestBudget budget) {
    return open(settings, credentials::value, credentials::renewedAfterRejection, budget);
  }

  private GraphClient open(
      SourceSettings settings,
      Supplier<String> token,
      Predicate<String> renewedAfterRejection,
      RequestBudget budget) {
    ProxyAndCredentials proxy = ProxyAndCredentials.parse(settings.sourceProxy(), null);
    return new GraphClient(
        URI.create(settings.sourceUrl()),
        token,
        renewedAfterRejection,
        httpClients.build(proxy.proxyHost(), proxy.proxyPort()),
        targetAddressValidator,
        new RateLimitHandling(
            RateLimitPolicy.of(Math.max(0, properties.maxRetries()), properties.maxRetryWait()),
            sleeper,
            budget),
        budget.meter(),
        properties.requestTimeout(),
        properties.downloadTimeout(),
        GraphClient.DEFAULT_MAX_JSON_BYTES);
  }
}
