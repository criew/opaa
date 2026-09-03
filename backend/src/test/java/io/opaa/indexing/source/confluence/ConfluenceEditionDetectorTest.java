package io.opaa.indexing.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.opaa.api.types.ConfluenceEdition;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ConfluenceEditionDetectorTest {

  private final ConfluenceEditionDetector detector =
      new ConfluenceEditionDetector(
          ConfluenceProperties.defaults(), TargetAddressValidator.disabled(), duration -> {});

  @Test
  void recognisesCloudBySiteSignatureAndStripsWikiFromTheAddress() throws Exception {
    try (FakeConfluenceServer cloud = new FakeConfluenceServer(ConfluenceEdition.CLOUD)) {
      ConfluenceEditionDetector.Detected detected =
          detector.detect(cloud.baseUrl() + "/wiki/", null, -1, false);

      assertThat(detected.edition()).isEqualTo(ConfluenceEdition.CLOUD);
      assertThat(detected.baseUrl().toString()).isEqualTo(cloud.baseUrl());
      assertThat(cloud.requests()).anyMatch(r -> r.equals("/_edge/tenant_info"));
    }
  }

  @Test
  void recognisesDataCenterBehindAContextPath() throws Exception {
    try (FakeConfluenceServer dc =
        new FakeConfluenceServer(ConfluenceEdition.DATA_CENTER, "/confluence")) {
      ConfluenceEditionDetector.Detected detected =
          detector.detect(dc.baseUrl() + "/", null, -1, false);

      assertThat(detected.edition()).isEqualTo(ConfluenceEdition.DATA_CENTER);
      assertThat(detected.baseUrl().toString()).isEqualTo(dc.baseUrl());
      assertThat(dc.requests()).anyMatch(r -> r.equals("/confluence/status"));
    }
  }

  @Test
  void neverGuessesFromTheHostName() throws Exception {
    try (FakeConfluenceServer dc = new FakeConfluenceServer(ConfluenceEdition.DATA_CENTER)) {
      // a Data Center answering on a loopback address is still Data Center, whatever the name
      assertThat(detector.detect(dc.baseUrl(), null, -1, false).edition())
          .isEqualTo(ConfluenceEdition.DATA_CENTER);
    }
  }

  @Test
  void somethingElseIsReportedAsNoConfluence() throws Exception {
    HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    other.createContext(
        "/",
        exchange -> {
          byte[] body = "<html>Intranet</html>".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/html");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    other.start();
    try {
      String url = "http://127.0.0.1:" + other.getAddress().getPort();
      assertThatThrownBy(() -> detector.detect(url, null, -1, false))
          .isInstanceOf(ConfluenceAccessException.NoConfluence.class)
          .hasMessageContaining("kein Confluence erkannt");
    } finally {
      other.stop(0);
    }
  }

  @Test
  void ssoRedirectToAnotherHostIsReportedAsNoConfluenceWithTheSsoHint() throws Exception {
    HttpServer sso = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    sso.createContext(
        "/",
        exchange -> {
          // every probe is bounced to a login page on a different host (different port = origin)
          exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/login");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    sso.start();
    try {
      String url = "http://127.0.0.1:" + sso.getAddress().getPort();
      assertThatThrownBy(() -> detector.detect(url, null, -1, false))
          .isInstanceOf(ConfluenceAccessException.NoConfluence.class)
          .hasMessageContaining("kein Confluence erkannt")
          .hasMessageContaining("SSO");
    } finally {
      sso.stop(0);
    }
  }

  @Test
  void rejectsAddressesWithCredentialsOrWithoutScheme() {
    assertThatThrownBy(() -> detector.detect("wiki.example.org", null, -1, false))
        .isInstanceOf(ConfluenceConnection.InvalidBaseUrlException.class);
    assertThatThrownBy(() -> detector.detect("https://user:pw@wiki.example.org", null, -1, false))
        .isInstanceOf(ConfluenceConnection.InvalidBaseUrlException.class)
        .hasMessageContaining("Zugangsdaten");
  }
}
