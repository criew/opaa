package io.opaa.indexing.source.s3;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.RunSecretContract;
import io.opaa.indexing.source.ScanJournal;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.SourceType;
import io.opaa.s3.S3Credentials;
import io.opaa.s3.S3TestFixture;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.ProductionDocumentFormats;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The S3 run against a real object store ({@link S3TestFixture}), so the SDK client signs with the
 * credentials the run asks for. Skipped without Docker; the CI runs it.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3RunSecretContractTest extends RunSecretContract {

  private static final int OBJECTS = 10;

  private S3TestFixture fixture;
  private String bucket;
  private S3Credentials renewed;
  private final List<String> authorizations = new CopyOnWriteArrayList<>();

  @BeforeEach
  void fill() {
    fixture = S3TestFixture.get();
    bucket = fixture.createBucket("geheimnis");
    for (int i = 1; i <= OBJECTS; i++) {
      fixture.putObject(bucket, "akte-" + i + ".txt", "Akte " + i, "text/plain");
    }
    renewed =
        fixture.createUser(S3TestFixture.policyAllowing(bucket, "s3:ListBucket", "s3:GetObject"));
  }

  @Override
  protected SourceSettings settings() {
    S3SourceSettings s3 =
        new S3SourceSettings(
            S3TestFixture.REGION, true, List.of(S3Scope.of(bucket, "")), null, null);
    return new SourceSettings(
        null,
        fixture.endpoint().toString(),
        null,
        fixture.rootCredentials().stored(),
        false,
        ConnectorData.fromJson(S3SourceSettingsJson.write(s3)));
  }

  @Override
  protected String renewedSecret() {
    return renewed.stored();
  }

  @Override
  protected boolean sawRenewedSecret() {
    return authorizations.stream()
        .anyMatch(header -> header.contains("Credential=" + renewed.accessKey() + "/"));
  }

  @Override
  protected boolean usesRejectionSeam() {
    return true;
  }

  @Override
  protected String refusedSecret() {
    return "UNBEKANNTERKEY:falscher-schluessel";
  }

  @Override
  protected SourceType type() {
    return S3SourceConnector.TYPE;
  }

  @Override
  protected int documents() {
    return OBJECTS;
  }

  @Override
  protected void run(IndexingRunTemplate template, UUID jobId, KnowledgeLibrary library) {
    SourceSyncStateRepository syncState = mock(SourceSyncStateRepository.class);
    when(syncState.findByLibraryId(any())).thenReturn(Optional.empty());
    when(syncState.save(any())).thenAnswer(call -> call.getArgument(0));
    S3Properties properties = S3Properties.defaults();
    new S3IndexingExecutor(
            new S3ClientFactory(
                properties,
                TargetAddressValidator.disabled(),
                request ->
                    authorizations.add(
                        request.firstMatchingHeader("Authorization").orElse("none"))),
            properties,
            ingestService,
            documentRepository,
            mock(LibraryFolderService.class),
            cleanupService,
            new ScanJournal(syncState),
            Clock.systemUTC(),
            template,
            ProductionDocumentFormats.supportedFormats())
        .execute(jobId, library, runMode());
  }
}
