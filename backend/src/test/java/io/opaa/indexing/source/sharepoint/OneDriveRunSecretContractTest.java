package io.opaa.indexing.source.sharepoint;

import static io.opaa.indexing.source.sharepoint.OneDriveTestDrives.DRIVE_ANNA;
import static io.opaa.indexing.source.sharepoint.OneDriveTestDrives.DRIVE_BERND;
import static io.opaa.indexing.source.sharepoint.OneDriveTestDrives.DRIVE_CARLA;
import static io.opaa.indexing.source.sharepoint.OneDriveTestDrives.TOKEN_ANNA;
import static io.opaa.indexing.source.sharepoint.OneDriveTestDrives.TOKEN_CARLA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.run.IndexingRunTemplate;
import io.opaa.indexing.spi.FullSyncRhythm;
import io.opaa.indexing.spi.FullSyncRhythmSettingRepository;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The OneDrive run of a private library against the {@link FakeGraphServer}, on the access token of
 * its owner's connected account, which the core hands out and renews after a {@code 401} like any
 * secret. A run only reads the OneDrive {@code me/drive} names for that token.
 */
class OneDriveRunSecretContractTest extends RunSecretContract {

  private static final int FILES = 10;
  private static final String RENEWED = "eyJ0eXAiOiJKV1QifQ.anna-erneuert";

  private FakeGraphServer server;
  private String token = TOKEN_ANNA;
  private String configured = DRIVE_ANNA;
  private IndexingRunMode mode = IndexingRunMode.FULL;

  @BeforeEach
  void serve() {
    server = new FakeGraphServer();
    OneDriveTestDrives.arrange(server);
    server.oneDrive(DRIVE_ANNA, "business", "anna@contoso.example", "Anna Amsel", RENEWED);
    for (int i = 1; i <= FILES; i++) {
      server.file(
          DRIVE_ANNA,
          "akte" + i,
          "Akte " + i + ".txt",
          "akten",
          ("Akte " + i).getBytes(StandardCharsets.UTF_8));
    }
    for (int i = 1; i <= FILES; i++) {
      server.file(
          DRIVE_BERND,
          "fremd" + i,
          "Fremd " + i + ".txt",
          FakeGraphServer.rootId(DRIVE_BERND),
          ("Fremd " + i).getBytes(StandardCharsets.UTF_8));
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
        token,
        OneDriveTestDrives.drive(configured, java.util.List.of("akten")));
  }

  @Override
  protected boolean ownerOnly() {
    return true;
  }

  @Override
  protected IndexingRunMode runMode() {
    return mode;
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
    return OneDriveSourceConnector.TYPE;
  }

  @Override
  protected int documents() {
    return FILES + 1;
  }

  @Override
  protected void run(IndexingRunTemplate template, UUID jobId, KnowledgeLibrary library) {
    SourceSyncStateRepository syncState = mock(SourceSyncStateRepository.class);
    when(syncState.findByLibraryId(any())).thenReturn(Optional.empty());
    when(syncState.save(any())).thenAnswer(call -> call.getArgument(0));
    new OneDriveIndexingExecutor(
            SharePointTestStores.connections(server),
            ingestService,
            documentRepository,
            mock(LibraryFolderService.class),
            new ScanJournal(syncState),
            new FullSyncRhythm(mock(FullSyncRhythmSettingRepository.class)),
            Clock.systemUTC(),
            template,
            ProductionDocumentFormats.supportedFormats())
        .execute(jobId, library, runMode());
  }

  // regression guard for ADR-0058, Entscheidung 3: a token never reads a OneDrive not its own
  @Test
  void aRunOnAnnasTokenForBerndsOneDriveFailsWithoutASingleRequestToIt() throws Exception {
    configured = DRIVE_BERND;
    UUID jobId = UUID.randomUUID();

    run(template(), jobId, library());

    verify(jobService).failJob(jobId, OwnDrive.OTHER_DRIVE);
    verify(ingestService, never()).ingest(any(), any());
    verifyNoInteractions(cleanupService);
    assertThat(OneDriveTestDrives.touched(server, DRIVE_BERND)).isFalse();
    assertThat(server.requests()).anyMatch(request -> request.startsWith("/v1.0/me/drive"));
  }

  @Test
  void aChangeRunIsRefusedBeforeItsFirstRequestToTheDriveToo() throws Exception {
    mode = IndexingRunMode.INCREMENTAL;
    configured = DRIVE_BERND;
    UUID jobId = UUID.randomUUID();

    run(template(), jobId, library());

    verify(jobService).failJob(jobId, OwnDrive.OTHER_DRIVE);
    verify(ingestService, never()).ingest(any(), any());
    assertThat(OneDriveTestDrives.touched(server, DRIVE_BERND)).isFalse();
  }

  @Test
  void aRunOnAnAccountWithoutOneDriveFailsWithItsOwnReason() throws Exception {
    token = "eyJ0eXAiOiJKV1QifQ.gast";
    server.alsoAccept(token);
    UUID jobId = UUID.randomUUID();

    run(template(), jobId, library());

    verify(jobService).failJob(jobId, OwnDrive.NO_DRIVE);
    assertThat(OneDriveTestDrives.touched(server, DRIVE_ANNA)).isFalse();
  }

  // regression guard for #3156: a token without any granted permission lacks the consent, not a
  // valid secret - the run ends with the consent's message, the connection does not expire
  @Test
  void aTokenWithoutConsentEndsTheRunWithoutRejectingTheSecret() throws Exception {
    server.failNextSaying(
        "me/drive",
        401,
        "InvalidAuthenticationToken",
        "Either scp or roles claim need to be present in the token.",
        5);
    UUID jobId = UUID.randomUUID();

    run(template(), jobId, library());

    verify(jobService).failJob(jobId, GraphClient.CONSENT_MISSING);
    verify(jobService, never()).failJob(eq(jobId), anyString(), anyString());
    assertThat(rejectionsReported()).as("the port heard of no rejection").isZero();
    assertThat(OneDriveTestDrives.touched(server, DRIVE_ANNA)).isFalse();
  }

  @Test
  void aRejectedTokenStillEndsTheRunAsRejected() throws Exception {
    server.failNext("me/drive", 401, "InvalidAuthenticationToken", null, 5);
    UUID jobId = UUID.randomUUID();

    run(template(), jobId, library());

    verify(jobService).failJob(eq(jobId), anyString(), eq("CREDENTIALS_REJECTED"));
    assertThat(rejectionsReported()).as("the port heard of the rejection once").isEqualTo(1);
  }

  @Test
  void aRunWhoseAccountMayNotReadItsOneDriveFailsWithItsOwnReason() throws Exception {
    server.failNext("me/drive", 403, "accessDenied", null, 1);
    UUID jobId = UUID.randomUUID();

    run(template(), jobId, library());

    verify(jobService).failJob(jobId, OwnDrive.NOT_READABLE);
    assertThat(OneDriveTestDrives.touched(server, DRIVE_ANNA)).isFalse();
  }

  @Test
  void aRunOnAPrivateMicrosoftAccountFailsWithItsOwnReason() throws Exception {
    token = TOKEN_CARLA;
    configured = DRIVE_CARLA;
    UUID jobId = UUID.randomUUID();

    run(template(), jobId, library());

    verify(jobService).failJob(jobId, OwnDrive.NOT_BUSINESS);
    verify(ingestService, never()).ingest(any(), any());
    assertThat(OneDriveTestDrives.touched(server, DRIVE_CARLA)).isFalse();
  }
}
