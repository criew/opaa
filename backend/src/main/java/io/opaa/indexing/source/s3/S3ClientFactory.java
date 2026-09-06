package io.opaa.indexing.source.s3;

import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import software.amazon.awssdk.http.SdkHttpRequest;

/**
 * Builds the {@link S3ObjectStore} for a library's {@link S3Connection} (ADR-0027, Entscheidung 8
 * and 9). Before a client exists, the endpoint host, the proxy host and - under virtual-host
 * addressing - every {@code <bucket>.<host>} the SDK will contact pass the shared {@link
 * TargetAddressValidator}, so a private on-premises address is rejected here exactly as it is for a
 * web directory, with the allowlist hint appended; the store then re-checks the host of every
 * request it sends.
 */
public class S3ClientFactory {

  private final S3Properties properties;
  private final TargetAddressValidator targetAddressValidator;
  private final Consumer<SdkHttpRequest> requestObserver;

  public S3ClientFactory(S3Properties properties, TargetAddressValidator targetAddressValidator) {
    this(properties, targetAddressValidator, null);
  }

  /** {@code requestObserver} sees every signed request before transmission - for tests. */
  S3ClientFactory(
      S3Properties properties,
      TargetAddressValidator targetAddressValidator,
      Consumer<SdkHttpRequest> requestObserver) {
    this.properties = properties;
    this.targetAddressValidator = targetAddressValidator;
    this.requestObserver = requestObserver;
  }

  /**
   * A store for the wizard's probes and the connection test - unbounded by a request budget.
   *
   * @param scopes the buckets the store will address; needed for the per-bucket host check under
   *     virtual-host addressing
   * @throws S3AccessException.TargetBlocked when endpoint, proxy or a bucket host is rejected
   */
  public S3ObjectStore create(S3Connection connection, Collection<S3Scope> scopes)
      throws S3AccessException {
    return create(connection, scopes, 0);
  }

  /**
   * A store for one indexing run: bounded by {@link S3Properties#requestBudgetPerRun} so the run
   * ends in an orderly way as truncated once the budget is spent.
   */
  public S3ObjectStore createForRun(S3Connection connection, Collection<S3Scope> scopes)
      throws S3AccessException {
    return create(connection, scopes, properties.requestBudgetPerRun());
  }

  private S3ObjectStore create(S3Connection connection, Collection<S3Scope> scopes, int budget)
      throws S3AccessException {
    validateTargets(connection, scopes);
    return new AwsSdkS3ObjectStore(
        connection, properties, targetAddressValidator, budget, requestObserver);
  }

  /**
   * The hosts this connection will contact, in the order a misconfiguration is most likely: the
   * endpoint itself, the proxy, then one virtual-host name per distinct bucket.
   */
  void validateTargets(S3Connection connection, Collection<S3Scope> scopes)
      throws S3AccessException.TargetBlocked {
    try {
      targetAddressValidator.validate(connection.endpoint());
      targetAddressValidator.validateHost(connection.proxyHost());
      if (!connection.pathStyle()) {
        Set<String> buckets = new LinkedHashSet<>();
        for (S3Scope scope : scopes) {
          buckets.add(scope.bucket());
        }
        for (String bucket : buckets) {
          targetAddressValidator.validateHost(bucket + "." + connection.endpoint().getHost());
        }
      }
    } catch (IOException e) {
      throw new S3AccessException.TargetBlocked(e.getMessage());
    }
  }
}
