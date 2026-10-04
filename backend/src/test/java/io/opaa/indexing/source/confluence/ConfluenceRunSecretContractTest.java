package io.opaa.indexing.source.confluence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.attachment.AttachmentProperties;
import io.opaa.indexing.document.AttachmentIndexer;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.RunSecretContract;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.knowledge.SourceType;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.test.ProductionDocumentFormats;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** The Confluence run against {@link FakeConfluenceServer} (Data Center, personal access token). */
class ConfluenceRunSecretContractTest extends RunSecretContract {

  private static final int PAGES = 5;
  private static final String TOKEN = "geheimes-token";
  private static final String RENEWED = "erneuertes-token";
  private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

  private FakeConfluenceServer server;

  @BeforeEach
  void serve() throws Exception {
    server = new FakeConfluenceServer(ConfluenceEdition.DATA_CENTER);
    server.addSpace("1", "ENG", "Engineering");
    for (int i = 1; i <= PAGES; i++) {
      server.addPage(
          String.valueOf(100 + i),
          "ENG",
          "Seite " + i,
          null,
          "<p>Inhalt " + i + ".</p>",
          NOW.minus(Duration.ofDays(3)));
    }
    server.addToken("dienst@behoerde.example", TOKEN, null);
    server.addToken("dienst@behoerde.example", RENEWED, null);
  }

  @AfterEach
  void stop() throws Exception {
    server.close();
  }

  /** Cloud answers 404 for refused credentials and for a wrong address alike: no rejection. */
  @org.junit.jupiter.api.Test
  void aCloud404OnTheSignInCheckIsNoRejectionOfTheSecret() throws Exception {
    com.sun.net.httpserver.HttpServer nothing =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    nothing.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    nothing.start();
    try {
      ConfluenceConnection connection =
          new ConfluenceConnection(
              java.net.URI.create("http://127.0.0.1:" + nothing.getAddress().getPort()),
              ConfluenceEdition.CLOUD,
              new ConfluenceCredentials.CloudApiToken("dienst@behoerde.example", "token"),
              null,
              -1,
              false);
      ConfluenceClient client =
          new ConfluenceClientFactory(properties(), TargetAddressValidator.disabled(), wait -> {})
              .create(connection);

      org.assertj.core.api.Assertions.assertThatThrownBy(client::verifyCredentials)
          .isInstanceOfSatisfying(
              ConfluenceAccessException.Authentication.class,
              e -> org.assertj.core.api.Assertions.assertThat(e.secretRejected()).isFalse());
    } finally {
      nothing.stop(0);
    }
  }

  private static ConfluenceProperties properties() {
    return new ConfluenceProperties(
        2,
        null,
        null,
        3,
        Duration.ofSeconds(2),
        0,
        0,
        0,
        Duration.ofDays(7),
        Duration.ofMinutes(10),
        0);
  }

  @Override
  protected SourceSettings settings() {
    ConfluenceSourceSettings confluence =
        new ConfluenceSourceSettings(
            ConfluenceEdition.DATA_CENTER,
            List.of(new ConfluenceSpaceSelection("ENG", null)),
            null);
    ConnectorData settings = confluence.sortedByKey().toData();
    return new SourceSettings(null, server.baseUrl(), null, TOKEN, false, settings);
  }

  @Override
  protected String renewedSecret() {
    return RENEWED;
  }

  @Override
  protected boolean sawRenewedSecret() {
    return server.authorizations().contains("Bearer " + RENEWED);
  }

  @Override
  protected String refusedSecret() {
    return "widerrufenes-token";
  }

  @Override
  protected SourceType type() {
    return ConfluenceSourceConnector.TYPE;
  }

  @Override
  protected int documents() {
    return PAGES;
  }

  @Override
  protected void run(IndexingRunTemplate template, UUID jobId, KnowledgeLibrary library) {
    SourceSyncStateRepository syncState = mock(SourceSyncStateRepository.class);
    when(syncState.findByLibraryId(any())).thenReturn(Optional.empty());
    when(syncState.save(any())).thenAnswer(call -> call.getArgument(0));
    ConfluenceProperties properties =
        new ConfluenceProperties(
            2,
            null,
            null,
            3,
            Duration.ofSeconds(2),
            0,
            0,
            0,
            Duration.ofDays(7),
            Duration.ofMinutes(10),
            0);
    new ConfluenceIndexingExecutor(
            new ConfluenceClientFactory(properties, TargetAddressValidator.disabled(), wait -> {}),
            properties,
            ingestService,
            new AttachmentIndexer(
                new BoundedDownloader(TargetAddressValidator.disabled()),
                ingestService,
                mock(LibraryStorageQuotaService.class),
                new AttachmentProperties(5, 0, 0),
                ProductionDocumentFormats.supportedFormats()),
            documentRepository,
            syncState,
            cleanupService,
            Clock.fixed(NOW, java.time.ZoneOffset.UTC),
            template)
        .execute(jobId, library, runMode());
  }
}
