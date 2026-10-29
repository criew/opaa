package io.opaa.indexing.spi;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.test.SourceTypes;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The library's credentials reach only the origin of its own source address when an original is
 * proxied - a document's stored address is content a feed operator controls.
 */
class RemoteOriginalAccessTest {

  private static final String CREDENTIALS = "feeduser:feedpass";
  private static final String EXPECTED_HEADER =
      "Basic " + Base64.getEncoder().encodeToString(CREDENTIALS.getBytes(StandardCharsets.UTF_8));

  private final List<HttpServer> servers = new ArrayList<>();

  private final RemoteOriginalAccess access =
      new RemoteOriginalAccess(
          new BoundedDownloader(TargetAddressValidator.disabled()),
          TargetAddressValidator.disabled(),
          new RemoteContentProperties(0, 0, 0));

  @AfterEach
  void stopServers() {
    servers.forEach(server -> server.stop(0));
  }

  @Test
  void theCredentialsReachAnOriginalOnTheFeedsOwnOrigin() throws IOException {
    List<String> received = new CopyOnWriteArrayList<>();
    String feedBase = serveOriginal(received);

    readOriginal(feedBase + "/feed.xml", feedBase + "/eintrag.pdf");

    assertThat(received).containsExactly(EXPECTED_HEADER);
  }

  // regression guard for #3338: an entry link on a foreign origin gets no feed credentials
  @Test
  void anOriginalOnAForeignOriginIsFetchedWithoutTheCredentials() throws IOException {
    String feedBase = serveOriginal(new CopyOnWriteArrayList<>());
    List<String> receivedByForeignHost = new CopyOnWriteArrayList<>();
    String foreignBase = serveOriginal(receivedByForeignHost);

    readOriginal(feedBase + "/feed.xml", foreignBase + "/eintrag.pdf");

    assertThat(receivedByForeignHost).containsExactly("<none>");
  }

  @Test
  void withoutAStoredSourceAddressNoCredentialsAreSent() throws IOException {
    List<String> received = new CopyOnWriteArrayList<>();
    String base = serveOriginal(received);

    readOriginal(null, base + "/eintrag.pdf");

    assertThat(received).containsExactly("<none>");
  }

  @Test
  void aCrawlsHttpToHttpsUpgradeKeepsTheCredentialsButAFeedEntryDoesNot() {
    String source = "http://intranet.example/akten/";
    String upgraded = "https://intranet.example/akten/bericht.pdf";

    assertThat(RemoteOriginalAccess.credentialsMayReachSubtree(source, upgraded, uri -> true))
        .isTrue();
    assertThat(RemoteOriginalAccess.credentialsMayReachOrigin(source, upgraded)).isFalse();
  }

  @Test
  void theCrawlsRuleRefusesADowngradeAnotherHostAndAnUnscopedPath() {
    String source = "https://intranet.example/akten/";

    assertThat(
            RemoteOriginalAccess.credentialsMayReachSubtree(
                source, "http://intranet.example/akten/a.pdf", uri -> true))
        .isFalse();
    assertThat(
            RemoteOriginalAccess.credentialsMayReachSubtree(
                source, "https://fremd.example/akten/a.pdf", uri -> true))
        .isFalse();
    assertThat(
            RemoteOriginalAccess.credentialsMayReachSubtree(
                source, "https://intranet.example/andere/a.pdf", uri -> false))
        .isFalse();
  }

  private void readOriginal(String sourceUrl, String documentUrl) throws IOException {
    Document document =
        new Document("eintrag.pdf", documentUrl, "application/pdf", null, SourceTypes.RSS_FEED);
    Optional<DocumentContent> content =
        access.openOriginal(document, null, new SourceSettings(null, sourceUrl, CREDENTIALS, null));
    assertThat(content).isPresent();
    try (InputStream stream = content.get().stream()) {
      assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("inhalt");
    }
  }

  /** Serves {@code /eintrag.pdf}, recording each request's Authorization header. */
  private String serveOriginal(List<String> receivedAuthorization) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/eintrag.pdf",
        exchange -> {
          String header = exchange.getRequestHeaders().getFirst("Authorization");
          receivedAuthorization.add(header == null ? "<none>" : header);
          byte[] body = "inhalt".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    servers.add(server);
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }
}
