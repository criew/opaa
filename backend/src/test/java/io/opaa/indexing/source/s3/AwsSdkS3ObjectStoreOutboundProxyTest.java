package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.s3.S3Connection;
import io.opaa.s3.S3Credentials;
import io.opaa.s3.StubS3Server;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.OutboundProxyRoute;
import io.opaa.test.RecordingHttpProxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The S3 connector takes the outbound proxy for its endpoint (the SDK's own client does not ask the
 * JVM's selector), and the target check lets the name pass that only the proxy resolves. {@code
 * s3.example.invalid} exists only behind the {@link RecordingHttpProxy}.
 */
class AwsSdkS3ObjectStoreOutboundProxyTest {

  @TempDir private Path tempDir;

  @Test
  void aListingGoesThroughTheOutboundProxy() throws Exception {
    try (StubS3Server server = new StubS3Server()) {
      server.addBucket("docs");
      server.putObject("docs", "a.pdf", "AAAA".getBytes(StandardCharsets.UTF_8), "application/pdf");
      URI stub = URI.create(server.endpoint());
      try (RecordingHttpProxy proxy =
              new RecordingHttpProxy()
                  .route("s3.example.invalid", new InetSocketAddress("127.0.0.1", stub.getPort()));
          OutboundProxyRoute route = OutboundProxyRoute.through(proxy.address())) {
        URI endpoint = URI.create("http://s3.example.invalid:" + stub.getPort());
        S3Connection connection =
            new S3Connection(
                endpoint,
                "us-east-1",
                true,
                new S3Credentials("AKIASTUBACCESSKEY", "geheim", null),
                null,
                -1,
                false);
        S3Properties properties =
            new S3Properties(
                100, 0, Duration.ofSeconds(5), 0, Duration.ofMillis(1), 0, tempDir, 0, 0);

        try (S3ObjectStore store =
            new S3ClientFactory(properties, new TargetAddressValidator(true, List.of()))
                .create(connection, List.of(S3Scope.of("docs", "")))) {
          S3ListPage page = store.listObjects(S3Scope.of("docs", ""), null);

          assertThat(page.objects()).extracting(S3ObjectSummary::key).containsExactly("a.pdf");
        }
        assertThat(proxy.requests())
            .singleElement()
            .asString()
            .startsWith("GET http://s3.example.invalid:" + stub.getPort() + "/docs");
      }
    }
  }
}
