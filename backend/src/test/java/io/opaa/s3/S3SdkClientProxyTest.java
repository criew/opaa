package io.opaa.s3;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.RecordingHttpProxy;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;

/**
 * Without a proxy in its settings the SDK client goes direct - the JVM's proxy properties are never
 * read, so the upload storage, which is infrastructure, stays off the outbound proxy.
 */
class S3SdkClientProxyTest {

  @Test
  void withoutAProxyTheJvmProxyPropertiesAreIgnored() throws Exception {
    String previousHost = System.getProperty("http.proxyHost");
    String previousPort = System.getProperty("http.proxyPort");
    try (StubS3Server server = new StubS3Server();
        RecordingHttpProxy proxy = new RecordingHttpProxy()) {
      System.setProperty("http.proxyHost", "127.0.0.1");
      System.setProperty("http.proxyPort", String.valueOf(proxy.port()));
      S3ClientSettings settings =
          new S3ClientSettings(
              URI.create(server.endpoint()),
              "us-east-1",
              true,
              AwsBasicCredentials.create("AKIA", "geheim"),
              null,
              0,
              Duration.ofSeconds(5),
              0,
              Duration.ofMillis(1));

      try (S3SdkClient client =
          S3SdkClient.open(settings, S3RequestGuard.targetCheckOnly(request -> {}))) {
        client.s3().listBuckets();
      }

      assertThat(server.seen()).isNotEmpty();
      assertThat(proxy.requests()).isEmpty();
    } finally {
      restore("http.proxyHost", previousHost);
      restore("http.proxyPort", previousPort);
    }
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
