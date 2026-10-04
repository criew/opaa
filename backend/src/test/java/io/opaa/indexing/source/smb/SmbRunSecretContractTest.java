package io.opaa.indexing.source.smb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.RunCredentials;
import io.opaa.indexing.source.RunSecretContract;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.SourceType;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.SourceRequestPolicy;
import io.opaa.test.MutableClock;
import io.opaa.test.ProductionDocumentFormats;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The SMB run against a real Samba ({@link SambaFixture}): one session for the run, the secret
 * still asked before every access, and a renewed one sent when a session is set up anew. Skipped
 * without Docker; the CI runs it.
 */
@Testcontainers(disabledWithoutDocker = true)
class SmbRunSecretContractTest extends RunSecretContract {

  private static final int FILES = 6;

  private SambaFixture samba;
  private String folder;

  @BeforeEach
  void fill() {
    samba = SambaFixture.get();
    folder = "geheimnis-" + UUID.randomUUID();
    for (int i = 1; i <= FILES; i++) {
      samba.put(folder + "/akte-" + i + ".txt", "Akte " + i + ".");
    }
  }

  @Override
  protected SourceSettings settings() {
    return samba.settings(samba.credentials(), List.of("/" + folder));
  }

  private static final String WRONG =
      SambaFixture.DOMAIN + "\\" + SambaFixture.USER + ":Falsches-Passwort-4711";

  /** SMB sends its secret when it signs in, so the next sign-in proves the renewal. */
  @Override
  protected void verifyRenewal() throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2026-10-04T12:00:00Z"));
    AtomicReference<String> stored = new AtomicReference<>(samba.credentials());
    RunCredentials credentials =
        new RunCredentials(() -> Secret.personal(stored.get()), RunCredentials.VALIDITY, clock);
    String file = folder + "/akte-1.txt";
    try (SmbShareClient client =
        SmbShareClient.of(
            SmbAddress.parse(samba.url(SambaFixture.SHARE)),
            credentials.derived(SmbCredentials::parse),
            TargetAddressValidator.disabled(),
            RequestBudget.unbounded(),
            Duration.ofSeconds(30))) {
      assertThat(client.find(file)).isPresent();
      stored.set(renewedSecret());
      clock.advance(RunCredentials.VALIDITY);

      assertThat(client.find(file)).as("the session signed in before stays").isPresent();
      client.reset();
      assertThatThrownBy(() -> client.find(file))
          .as("the next sign-in sends the renewed secret")
          .isInstanceOf(SmbAccessException.Authentication.class);
    }
  }

  @Override
  protected String renewedSecret() {
    return WRONG;
  }

  @Override
  protected boolean sawRenewedSecret() {
    throw new UnsupportedOperationException("proven by the sign-in in verifyRenewal");
  }

  @Override
  protected boolean usesRejectionSeam() {
    return true;
  }

  @Override
  protected String refusedSecret() {
    return WRONG;
  }

  @Override
  protected SourceType type() {
    return SmbSourceConnector.TYPE;
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
    new SmbIndexingExecutor(
            SmbProperties.defaults(),
            TargetAddressValidator.disabled(),
            SourceRequestPolicy.defaults(),
            ingestService,
            documentRepository,
            mock(LibraryFolderService.class),
            cleanupService,
            syncState,
            Clock.systemUTC(),
            template,
            ProductionDocumentFormats.supportedFormats())
        .execute(jobId, library, runMode());
  }
}
