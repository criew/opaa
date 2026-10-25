package io.opaa.indexing.source.sharepoint;

import static io.opaa.indexing.source.sharepoint.SharePointTestStores.DRIVE_0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.indexing.run.IndexingRunTemplate;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.RunSecretContract;
import io.opaa.indexing.spi.ScanJournal;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.SourceType;
import io.opaa.msgraph.FakeGraphServer;
import io.opaa.msgraph.GraphClient;
import io.opaa.test.ProductionDocumentFormats;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The SharePoint run against the {@link FakeGraphServer}, the access token being what the core
 * hands out for the profile's client credentials.
 */
class SharePointRunSecretContractTest extends RunSecretContract {

  private static final int FILES = 10;
  private static final String RENEWED = "eyJ0eXAiOiJKV1QifQ.erneuert";

  private FakeGraphServer server;

  @BeforeEach
  void serve() {
    server = new FakeGraphServer();
    server.alsoAccept(RENEWED);
    SharePointTestStores.twoLibraries(server);
    server.folder(DRIVE_0, "akten", "Akten", FakeGraphServer.rootId(DRIVE_0));
    for (int i = 1; i <= FILES; i++) {
      server.file(
          DRIVE_0,
          "akte" + i,
          "Akte " + i + ".txt",
          "akten",
          ("Akte " + i).getBytes(StandardCharsets.UTF_8));
    }
  }

  @AfterEach
  void stop() {
    server.close();
  }

  @Override
  protected SourceSettings settings() {
    return new SourceSettings(
        null,
        server.origin().toString(),
        FakeGraphServer.TOKEN,
        ConnectorData.of(Map.of("libraries", List.of(Map.of("driveId", DRIVE_0)))));
  }

  @Override
  protected String renewedSecret() {
    return RENEWED;
  }

  @Override
  protected boolean sawRenewedSecret() {
    return server.tokens().contains(RENEWED);
  }

  @Override
  protected boolean usesRejectionSeam() {
    return true;
  }

  @Override
  protected String refusedSecret() {
    return "eyJ0eXAiOiJKV1QifQ.widerrufen";
  }

  @Override
  protected SourceType type() {
    return SharePointSourceConnector.TYPE;
  }

  @Override
  protected int documents() {
    return FILES;
  }

  @Override
  protected void run(IndexingRunTemplate template, UUID jobId, KnowledgeLibrary library) {
    SourceSyncStateRepository syncState = mock(SourceSyncStateRepository.class);
    when(syncState.findByLibraryId(any())).thenReturn(Optional.empty());
    when(syncState.save(any())).thenAnswer(call -> call.getArgument(0));
    new SharePointIndexingExecutor(
            SharePointTestStores.connections(server),
            ingestService,
            documentRepository,
            mock(LibraryFolderService.class),
            new ScanJournal(syncState),
            Clock.systemUTC(),
            template,
            ProductionDocumentFormats.supportedFormats())
        .execute(jobId, library, runMode());
  }

  // regression guard for #3156: a token without any granted permission lacks the administrator
  // consent, not a valid secret - the run ends with the consent's message, the access stays valid
  @Test
  void aTokenWithoutConsentEndsTheRunWithoutRejectingTheSecret() throws Exception {
    server.failNextSaying(
        "drives/" + DRIVE_0,
        401,
        "InvalidAuthenticationToken",
        "Either scp or roles claim need to be present in the token.",
        5);
    UUID jobId = UUID.randomUUID();

    run(template(), jobId, library());

    verify(jobService).failJob(jobId, GraphClient.CONSENT_MISSING);
    verify(jobService, never()).failJob(eq(jobId), anyString(), anyString());
    verify(ingestService, never()).ingest(any(), any());
    assertThat(rejectionsReported()).as("the port heard of no rejection").isZero();
  }

  @Test
  void aRejectedTokenStillEndsTheRunAsRejected() throws Exception {
    server.failNext("drives/" + DRIVE_0, 401, "InvalidAuthenticationToken", null, 5);
    UUID jobId = UUID.randomUUID();

    run(template(), jobId, library());

    verify(jobService).failJob(eq(jobId), anyString(), eq("CREDENTIALS_REJECTED"));
    assertThat(rejectionsReported()).as("the port heard of the rejection once").isEqualTo(1);
  }
}
