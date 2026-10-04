package io.opaa.indexing.source.smb;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.RunSecretContract;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.SourceType;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.SourceRequestPolicy;
import io.opaa.test.ProductionDocumentFormats;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The SMB run against a real Samba ({@link SambaFixture}): one session for the run, the secret
 * still asked before every access. Skipped without Docker; the CI runs it.
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
