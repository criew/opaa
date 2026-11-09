package io.opaa.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.observability.OutboundMetrics;
import io.opaa.observability.OutboundTarget;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

/**
 * Every attempt of the SDK client counts in {@code opaa.outbound.requests} with target {@code
 * connector} and kind {@code s3}, a retry included, and with no bucket, host or address as a tag.
 */
class S3OutboundMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    status("/ok", 200);
    status("/denied", 403);
    status("/busy", 429);
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private void status(String path, int status) {
    server.createContext(
        path,
        exchange -> {
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
  }

  @Test
  void anAnswerOfTheStoreCountsWithItsOutcome() {
    headBucket("ok");
    headBucket("denied");

    assertThat(count("success")).isEqualTo(1);
    assertThat(count("auth_error")).isEqualTo(1);
    assertThat(registry.find(OutboundMetrics.REQUESTS).timers())
        .allSatisfy(
            timer ->
                assertThat(timer.getId().getTags())
                    .extracting(Tag::getValue)
                    .noneMatch(value -> value.contains("127.0.0.1") || value.contains("denied")));
  }

  @Test
  void everyThrottledAttemptCountsAsThrottled() {
    headBucket("busy");

    assertThat(count("throttled")).isEqualTo(2);
    assertThat(registry.get(OutboundMetrics.THROTTLED).counter().count()).isEqualTo(2);
  }

  private void headBucket(String bucket) {
    try (S3SdkClient client =
        S3SdkClient.open(
            new S3ClientSettings(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "eu-central-1",
                true,
                AwsBasicCredentials.create("AKIA", "geheim"),
                null,
                0,
                Duration.ofSeconds(5),
                1,
                Duration.ofMillis(10)),
            S3RequestGuard.targetCheckOnly(request -> {}),
            new OutboundMetrics(registry, OutboundTarget.CONNECTOR, "s3"))) {
      catchThrowable(
          () -> client.s3().headBucket(HeadBucketRequest.builder().bucket(bucket).build()));
    }
  }

  private long count(String outcome) {
    Timer timer =
        registry
            .find(OutboundMetrics.REQUESTS)
            .tag("target", "connector")
            .tag("kind", "s3")
            .tag("outcome", outcome)
            .timer();
    return timer == null ? 0 : timer.count();
  }
}
