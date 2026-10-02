package io.opaa.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.opaa.security.RebindingHostLookup;
import io.opaa.security.TargetAddressValidator;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;

/**
 * Regression guard for #1860: the S3 client checks the address it connects to, not only the host
 * its guard saw before transmission. {@code localhost} answers a public address to the guard and
 * the loopback to the connection; the request must end as {@link S3AccessException.TargetBlocked}
 * before it reaches the local endpoint listening there.
 */
class S3DnsRebindingTest {

  private final AtomicInteger requests = new AtomicInteger();
  private HttpServer server;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          byte[] body =
              "<ListAllMyBucketsResult><Buckets/></ListAllMyBucketsResult>"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/xml");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void anEndpointThatRebindsAfterTheGuardIsRefusedAtConnectTime() {
    RebindingHostLookup lookup = new RebindingHostLookup("localhost");
    TargetAddressValidator validator = new TargetAddressValidator(true, List.of(), lookup);
    S3ClientSettings settings =
        new S3ClientSettings(
            URI.create("http://localhost:" + server.getAddress().getPort()),
            "eu-central-1",
            true,
            AwsBasicCredentials.create("AKIA", "geheim"),
            null,
            0,
            false,
            Duration.ofSeconds(5),
            0,
            Duration.ofMillis(10));
    S3FailureTranslator translator =
        new S3FailureTranslator(Duration.ofSeconds(5), 0, "ALLOWLIST-HINWEIS");

    try (S3SdkClient client =
        S3SdkClient.open(
            settings,
            S3RequestGuard.targetCheckOnly(S3RequestGuard.TargetPolicy.hostOnly(validator)))) {
      assertThatThrownBy(
              () ->
                  translator.call(
                      S3Operation.LIST_BUCKETS, null, null, () -> client.s3().listBuckets()))
          .isInstanceOf(S3AccessException.TargetBlocked.class)
          .hasMessageContaining("gesperrten Adressbereich");
    }

    assertThat(requests).hasValue(0);
    assertThat(lookup.lookups()).isEqualTo(2);
  }
}
