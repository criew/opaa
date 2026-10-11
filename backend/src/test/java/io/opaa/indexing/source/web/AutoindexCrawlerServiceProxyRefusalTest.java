package io.opaa.indexing.source.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.opaa.outbound.ProxyRefusal;
import io.opaa.security.TargetAddressValidator;
import java.io.IOException;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A {@code 407} on a directory page comes from the outbound proxy - a server answering it directly
 * looks the same to the client - and ends the crawl with the German proxy message the run reports.
 */
class AutoindexCrawlerServiceProxyRefusalTest {

  private HttpServer server;
  private String origin;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(407, -1);
          exchange.close();
        });
    server.start();
    origin = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void aStartPageAnswered407EndsTheCrawlWithTheProxyMessage() {
    AutoindexCrawlerService service =
        new AutoindexCrawlerService(
            TargetAddressValidator.disabled(), new CrawlProperties(10, 100, 0));

    assertThatThrownBy(() -> service.crawl(origin + "/dokumente/", "admin", "secret", false))
        .isInstanceOf(ProxyRefusal.RefusedException.class)
        .hasMessageStartingWith("Der Ausgangsproxy hat die Verbindung zum Ziel abgelehnt")
        .hasMessageContaining("Status 407");
  }
}
