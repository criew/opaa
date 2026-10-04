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
