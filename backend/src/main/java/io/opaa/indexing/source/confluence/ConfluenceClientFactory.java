package io.opaa.indexing.source.confluence;

import io.opaa.sourceaccess.SourceHttpClientFactory;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.net.http.HttpClient;

/**
 * Builds the {@link ConfluenceClient} for a library's {@link ConfluenceConnection} - the adapter is
 * chosen by the connection's edition, never by inspecting the address - and the credential-free
 * {@link ConfluenceEditionDetector}. Shares the target validation every other outbound source fetch
 * uses, so a private on-premises address is rejected here exactly as it is for a web directory,
 * with the allowlist hint appended to the message.
 */
public class ConfluenceClientFactory {

  private final ConfluenceProperties properties;
  private final TargetAddressValidator targetAddressValidator;
  private final Sleeper sleeper;

  public ConfluenceClientFactory(
      ConfluenceProperties properties, TargetAddressValidator targetAddressValidator) {
    this(properties, targetAddressValidator, Sleeper.threadSleep());
  }

  ConfluenceClientFactory(
      ConfluenceProperties properties,
      TargetAddressValidator targetAddressValidator,
      Sleeper sleeper) {
    this.properties = properties;
    this.targetAddressValidator = targetAddressValidator;
    this.sleeper = sleeper;
  }

  /**
   * @throws ConfluenceAccessException when the proxy host fails the target validation (the target
   *     itself is validated on every request the client sends)
   */
  public ConfluenceClient create(ConfluenceConnection connection) throws ConfluenceAccessException {
    return create(connection, 0);
  }

  /**
   * A client for one indexing run (#1141): bounded by {@link
   * ConfluenceProperties#requestBudgetPerRun} so the run ends in an orderly way as incomplete once
   * the budget is spent. The wizard's probes and the edition detection use {@link #create} - they
   * have no run to continue in.
   */
  public ConfluenceClient createForRun(ConfluenceConnection connection)
      throws ConfluenceAccessException {
    return create(connection, properties.requestBudgetPerRun());
  }

  private ConfluenceClient create(ConfluenceConnection connection, int requestBudget)
      throws ConfluenceAccessException {
    ConfluenceHttp.validateProxy(targetAddressValidator, connection.proxyHost());
    if (connection.credentials() == null) {
      throw new IllegalArgumentException("a ConfluenceClient needs credentials");
    }
    if (connection.credentials().edition() != connection.edition()) {
      throw new IllegalArgumentException(
          "credentials are for "
              + connection.credentials().edition()
              + ", connection is "
              + connection.edition());
    }
    HttpClient httpClient =
        SourceHttpClientFactory.buildHttpClient(
            connection.proxyHost(), connection.proxyPort(), connection.insecureSsl());
    ConfluenceHttp http =
        new ConfluenceHttp(
            httpClient,
            connection,
            properties,
            targetAddressValidator,
            sleeper,
            new ConfluenceRequestMeter(),
            null,
            requestBudget);
    return switch (connection.edition()) {
      case CLOUD -> new CloudConfluenceClient(http);
      case DATA_CENTER -> new DataCenterConfluenceClient(http);
    };
  }

  public ConfluenceEditionDetector editionDetector() {
    return new ConfluenceEditionDetector(properties, targetAddressValidator, sleeper);
  }
}
