package io.opaa.sourceaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.LoopbackTls;
import io.opaa.test.OutboundProxyRoute;
import io.opaa.test.RecordingHttpProxy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The source-access client takes the outbound proxy without a proxy of its own: plain {@code http}
 * in absolute form, {@code https} as a tunnel, every redirect hop again through the proxy; an
 * excluded target goes direct. {@code upstream.invalid} exists only behind the {@link
 * RecordingHttpProxy}, so reaching it at all proves the route.
 */
class SourceHttpClientOutboundProxyTest {

  private HttpServer upstream;
  private int upstreamPort;
  private RecordingHttpProxy proxy;

  @BeforeEach
  void start() throws IOException {
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstreamPort = upstream.getAddress().getPort();
    upstream.createContext(
        "/start",
        exchange -> {
          exchange
              .getResponseHeaders()
              .add("Location", "http://upstream.invalid:" + upstreamPort + "/target");
          respond(exchange, 302, "");
        });
    upstream.createContext("/target", exchange -> respond(exchange, 200, "content"));
    upstream.start();
    proxy = new RecordingHttpProxy().route("upstream.invalid", upstream.getAddress());
  }

  @AfterEach
  void stop() throws IOException {
    proxy.close();
    upstream.stop(0);
  }

  @Test
  void aSourceRequestAndEveryRedirectHopGoThroughTheProxy() throws Exception {
    try (OutboundProxyRoute route = OutboundProxyRoute.through(proxy.address())) {
      HttpClient client = SourceHttpClientFactory.buildHttpClient(null, 0, false);

      HttpResponse<InputStream> response =
          RedirectFollowingFetcher.sendFollowingRedirects(
              client,
              "http://upstream.invalid:" + upstreamPort + "/start",
              Duration.ofSeconds(10),
              Map.of(),
              new TargetAddressValidator(true, List.of()),
              RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN);

      assertThat(response.statusCode()).isEqualTo(200);
      try (InputStream body = response.body()) {
        assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("content");
      }
      assertThat(proxy.requests())
          .containsExactly(
              "GET http://upstream.invalid:" + upstreamPort + "/start",
              "GET http://upstream.invalid:" + upstreamPort + "/target");
    }
  }

  @Test
  void anExcludedTargetGoesDirect() throws Exception {
    try (OutboundProxyRoute route =
        OutboundProxyRoute.through(proxy.address(), "upstream.invalid")) {
      HttpClient client = SourceHttpClientFactory.buildHttpClient(null, 0, false);

      // Direct, the name does not resolve; through the proxy the request would have succeeded.
      assertThatThrownBy(
              () ->
                  RedirectFollowingFetcher.sendFollowingRedirects(
                      client,
                      "http://upstream.invalid:" + upstreamPort + "/target",
                      Duration.ofSeconds(10),
                      Map.of(),
                      TargetAddressValidator.disabled(),
                      RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN))
          .isInstanceOf(IOException.class);
      assertThat(proxy.requests()).isEmpty();
    }
  }

  @Test
  void anHttpsTargetIsTunnelledWithConnect() throws Exception {
    HttpsServer tlsUpstream = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    tlsUpstream.setHttpsConfigurator(new HttpsConfigurator(LoopbackTls.serverContext()));
    tlsUpstream.createContext("/secure", exchange -> respond(exchange, 200, "tunnelled"));
    tlsUpstream.start();
    proxy.route("secure.invalid", tlsUpstream.getAddress());
    try (OutboundProxyRoute route = OutboundProxyRoute.through(proxy.address())) {
      // Trusts the loopback certificate for any name: the test is about the route, not TLS.
      HttpClient client = HttpClient.newBuilder().sslContext(trustingAnyName()).build();
      int port = tlsUpstream.getAddress().getPort();

      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(
                      java.net.URI.create("https://secure.invalid:" + port + "/secure"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertThat(response.body()).isEqualTo("tunnelled");
      assertThat(proxy.requests()).containsExactly("CONNECT secure.invalid:" + port);
    } finally {
      tlsUpstream.stop(0);
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static SSLContext trustingAnyName() throws Exception {
    X509ExtendedTrustManager trustAll =
        new X509ExtendedTrustManager() {
          @Override
          public void checkClientTrusted(X509Certificate[] chain, String authType) {}

          @Override
          public void checkServerTrusted(X509Certificate[] chain, String authType) {}

          @Override
          public void checkClientTrusted(X509Certificate[] chain, String authType, Socket s) {}

          @Override
          public void checkServerTrusted(X509Certificate[] chain, String authType, Socket s) {}

          @Override
          public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine e) {}

          @Override
          public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine e) {}

          @Override
          public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
          }
        };
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, new TrustManager[] {trustAll}, new SecureRandom());
    return context;
  }
}
