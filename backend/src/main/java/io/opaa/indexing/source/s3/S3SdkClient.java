package io.opaa.indexing.source.s3;

import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.http.apache5.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.retries.api.RetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.utils.AttributeMap;

/**
 * The one way an SDK client is built in this application (ADR-0027, Entscheidung 8 and 9; ADR-0030,
 * Entscheidung 8): static credentials and an explicit region, so the SDK's own resolution chains
 * never run; the endpoint and addressing style as configured; request and response checksums only
 * where the protocol requires them, because S3-compatible stores do not all understand the SDK's
 * newer checksums; the Apache 5 client with one timeout for connection, socket and attempt; a proxy
 * and relaxed TLS when asked; and an exponential, capped retry strategy without a circuit breaker.
 * Holds the HTTP connection pool and must be closed.
 */
public final class S3SdkClient implements AutoCloseable {

  private static final Duration MAX_BACKOFF = Duration.ofSeconds(20);

  private final SdkHttpClient httpClient;
  private final S3Client s3;

  private S3SdkClient(SdkHttpClient httpClient, S3Client s3) {
    this.httpClient = httpClient;
    this.s3 = s3;
  }

  /**
   * Builds the client; sends nothing. {@code guard} sees every wire attempt before it leaves and
   * every answer that comes back.
   */
  public static S3SdkClient open(S3ClientSettings settings, ExecutionInterceptor guard) {
    SdkHttpClient httpClient = buildHttpClient(settings);
    try {
      S3Client s3 =
          S3Client.builder()
              .endpointOverride(settings.endpoint())
              .region(Region.of(settings.region()))
              .forcePathStyle(settings.pathStyle())
              .credentialsProvider(StaticCredentialsProvider.create(settings.credentials()))
              .httpClient(httpClient)
              .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
              .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
              .overrideConfiguration(
                  override ->
                      override
                          .retryStrategy(retryStrategy(settings))
                          .apiCallAttemptTimeout(settings.requestTimeout())
                          .addExecutionInterceptor(guard))
              .build();
      return new S3SdkClient(httpClient, s3);
    } catch (RuntimeException e) {
      httpClient.close();
      throw e;
    }
  }

  private static SdkHttpClient buildHttpClient(S3ClientSettings settings) {
    Duration timeout = settings.requestTimeout();
    Apache5HttpClient.Builder http =
        Apache5HttpClient.builder().connectionTimeout(timeout).socketTimeout(timeout);
    if (settings.hasProxy()) {
      http.proxyConfiguration(
          ProxyConfiguration.builder()
              .endpoint(URI.create("http://" + settings.proxyHost() + ":" + settings.proxyPort()))
              .useSystemPropertyValues(false)
              .useEnvironmentVariableValues(false)
              .build());
    }
    return http.buildWithDefaults(
        AttributeMap.builder()
            .put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, settings.insecureSsl())
            .build());
  }

  private static RetryStrategy retryStrategy(S3ClientSettings settings) {
    Duration base = settings.retryBackoff();
    Duration max = base.multipliedBy(1L << Math.min(settings.maxRetries(), 10));
    if (max.compareTo(MAX_BACKOFF) > 0) {
      max = MAX_BACKOFF;
    }
    BackoffStrategy backoff = BackoffStrategy.exponentialDelay(base, max);
    return AwsRetryStrategy.standardRetryStrategy().toBuilder()
        .maxAttempts(settings.maxRetries() + 1)
        .backoffStrategy(backoff)
        .throttlingBackoffStrategy(backoff)
        .circuitBreakerEnabled(false)
        .build();
  }

  public S3Client s3() {
    return s3;
  }

  @Override
  public void close() {
    s3.close();
    httpClient.close();
  }
}
