package io.opaa.library;

import static io.opaa.library.SourceConnectionTestBuilder.sourceConnectionTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.DocumentService;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.source.filesystem.FilesystemPathAllowlist;
import io.opaa.indexing.source.rss.RssFeedParser;
import io.opaa.indexing.source.s3.S3Scope;
import io.opaa.indexing.source.s3.S3SourceSettings;
import io.opaa.indexing.source.web.AutoindexCrawlerService;
import io.opaa.sourceaccess.SourceRequestPolicy;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The S3 branch of {@link SourceConnectionTestService} (#1376): the effective request handed to
 * {@link S3ConnectionService}, the stored fallback for credentials, proxy, TLS <em>and</em> the
 * scopes on the same origin, and the permission bar and type check of the bucket listing.
 */
class SourceConnectionTestServiceS3Test {

  private static final S3SourceSettings SETTINGS =
      new S3SourceSettings(null, true, List.of(S3Scope.of("dokumente", "")), null, null);
  private static final S3SourceSettings STORED_SETTINGS =
      new S3SourceSettings(
          "eu-central-1", true, List.of(S3Scope.of("protokolle", "2025/")), null, null);

  private KnowledgeLibraryRepository libraryRepository;
  private LibraryAccessService libraryAccessService;
  private S3ConnectionService s3ConnectionService;
  private SourceConnectionTestService service;
  private UUID currentUserId;
  private UUID organizationId;
  private CurrentUser caller;

  @BeforeEach
  void setUp() {
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    libraryAccessService = mock(LibraryAccessService.class);
    s3ConnectionService = mock(S3ConnectionService.class);
    currentUserId = UUID.randomUUID();
    organizationId = UUID.randomUUID();
    caller = CurrentUser.of(currentUserId, organizationId, SystemRole.USER, "Caller");
    service =
        new SourceConnectionTestService(
            new DocumentService(),
            new AutoindexCrawlerService(TargetAddressValidator.disabled()),
            new RssFeedParser(),
            mock(FilesystemPathAllowlist.class),
            libraryRepository,
            libraryAccessService,
            new IndexingProperties(1000, 0, 50, null, null, null, null, 0),
            TargetAddressValidator.disabled(),
            SourceRequestPolicy.defaults(),
            mock(ConfluenceConnectionService.class),
            s3ConnectionService);
  }

  private KnowledgeLibrary s3Library(UUID libraryId, String url) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Protokolle",
            null,
            currentUserId,
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.S3,
            null,
            url,
            "proxy.stored.example:3128",
            "AKIASTORED:stored-secret",
            true);
    library.updateS3Settings(STORED_SETTINGS);
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    return library;
  }

  private static S3ConnectionService.Probe ok() {
    return new S3ConnectionService.Probe(true, "ok", true, List.of(), 0L);
  }

  @Test
  void connectionTestDelegatesTheEffectiveRequestAndCarriesTheScopeFindings() throws Exception {
    S3ScopeCheck check = new S3ScopeCheck("dokumente", "", true, true, true, 3, false, null);
    when(s3ConnectionService.probe("https://s3.example.org", null, "ak:sk", false, SETTINGS))
        .thenReturn(new S3ConnectionService.Probe(true, "erreichbar", true, List.of(check), 3L));

    SourceConnectionTestResult result =
        service.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.S3)
                .sourceUrl(URI.create("https://s3.example.org"))
                .sourceCredentials("ak:sk")
                .s3Settings(SETTINGS)
                .build(),
            caller);

    assertThat(result.reachable()).isTrue();
    assertThat(result.documentCount()).isEqualTo(3);
    assertThat(result.credentialsVerified()).isTrue();
    assertThat(result.confluenceEdition()).isNull();
    assertThat(result.s3Scopes()).containsExactly(check);
  }

  @Test
  void connectionTestRejectsAPathAndRequiresTheEndpoint() {
    assertThatThrownBy(
            () ->
                service.test(
                    sourceConnectionTest()
                        .sourceType(DocumentSourceType.S3)
                        .sourcePath("/srv/docs")
                        .sourceUrl(URI.create("https://s3.example.org"))
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourcePath");
    assertThatThrownBy(
            () ->
                service.test(
                    sourceConnectionTest().sourceType(DocumentSourceType.S3).build(), caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceUrl");
  }

  @Test
  void connectionTestFallsBackToStoredCredentialsProxyTlsAndScopesOnTheSameOrigin()
      throws Exception {
    UUID libraryId = UUID.randomUUID();
    s3Library(libraryId, "https://s3.example.org");
    when(s3ConnectionService.probe(anyString(), any(), anyString(), anyBoolean(), any()))
        .thenReturn(ok());

    service.test(
        sourceConnectionTest()
            .sourceType(DocumentSourceType.S3)
            .sourceUrl(URI.create("https://s3.example.org/"))
            .sourceProxy("attacker.example:8080")
            .sourceInsecureSsl(false)
            .libraryId(libraryId)
            .build(),
        caller);

    // #617: the stored proxy and TLS switch win whenever the stored credential is replayed; the
    // stored scopes stand in because the request named none
    verify(s3ConnectionService)
        .probe(
            "https://s3.example.org/",
            "proxy.stored.example:3128",
            "AKIASTORED:stored-secret",
            true,
            STORED_SETTINGS);
    verify(libraryAccessService)
        .requireRole(any(), eq(currentUserId), eq(false), eq(AssetRole.MANAGER));
  }

  @Test
  void connectionTestDoesNotReplayTheStoredKeyForAnotherOriginButStillUsesTheStoredScopes()
      throws Exception {
    UUID libraryId = UUID.randomUUID();
    s3Library(libraryId, "https://s3.example.org");
    when(s3ConnectionService.probe(anyString(), any(), any(), anyBoolean(), any()))
        .thenReturn(ok());

    service.test(
        sourceConnectionTest()
            .sourceType(DocumentSourceType.S3)
            .sourceUrl(URI.create("https://s3.other.example"))
            .libraryId(libraryId)
            .build(),
        caller);

    verify(s3ConnectionService)
        .probe("https://s3.other.example", null, null, false, STORED_SETTINGS);
  }

  @Test
  void bucketListingRequiresCredentialsWithoutALibraryToFallBackOn() {
    assertThatThrownBy(
            () ->
                service.listS3Buckets(
                    new S3BucketListingRequest(
                        URI.create("https://s3.example.org"), null, null, null, null, true, null),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceCredentials");
    assertThatThrownBy(
            () ->
                service.listS3Buckets(
                    new S3BucketListingRequest(null, "ak:sk", null, null, null, true, null),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceUrl");
  }

  @Test
  void bucketListingFallsBackToStoredCredentialsAndForcesProxyAndTls() throws Exception {
    UUID libraryId = UUID.randomUUID();
    s3Library(libraryId, "https://s3.example.org");
    when(s3ConnectionService.listBuckets(
            anyString(), any(), anyString(), anyBoolean(), any(), anyBoolean()))
        .thenReturn(new S3BucketListResult(true, List.of("protokolle"), null));

    S3BucketListResult result =
        service.listS3Buckets(
            new S3BucketListingRequest(
                URI.create("https://s3.example.org"),
                null,
                "attacker.example:8080",
                false,
                "eu-west-1",
                false,
                libraryId),
            caller);

    assertThat(result.buckets()).containsExactly("protokolle");
    verify(s3ConnectionService)
        .listBuckets(
            "https://s3.example.org",
            "proxy.stored.example:3128",
            "AKIASTORED:stored-secret",
            true,
            "eu-west-1",
            false);

    // without region and style in the request, the stored settings sign the listing
    service.listS3Buckets(
        new S3BucketListingRequest(
            URI.create("https://s3.example.org"), null, null, null, null, null, libraryId),
        caller);
    verify(s3ConnectionService)
        .listBuckets(
            "https://s3.example.org",
            "proxy.stored.example:3128",
            "AKIASTORED:stored-secret",
            true,
            "eu-central-1",
            true);
  }

  @Test
  void anInterruptedProbeIsReportedNotThrown() throws Exception {
    when(s3ConnectionService.probe(anyString(), any(), anyString(), anyBoolean(), any()))
        .thenThrow(new InterruptedException());

    SourceConnectionTestResult result =
        service.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.S3)
                .sourceUrl(URI.create("https://s3.example.org"))
                .sourceCredentials("ak:sk")
                .s3Settings(SETTINGS)
                .build(),
            caller);

    assertThat(Thread.interrupted()).as("interrupt flag restored").isTrue();
    assertThat(result.reachable()).isFalse();
    assertThat(result.message()).contains("unterbrochen");
  }

  @Test
  void bucketListingRequiresManagerAndAnS3Library() throws Exception {
    UUID libraryId = UUID.randomUUID();
    s3Library(libraryId, "https://s3.example.org");
    Mockito.doThrow(new AccessDeniedException("nein"))
        .when(libraryAccessService)
        .requireRole(any(), eq(currentUserId), eq(false), eq(AssetRole.MANAGER));
    assertThatThrownBy(
            () ->
                service.listS3Buckets(
                    new S3BucketListingRequest(
                        URI.create("https://s3.example.org"),
                        null,
                        null,
                        null,
                        null,
                        true,
                        libraryId),
                    caller))
        .isInstanceOf(AccessDeniedException.class);

    UUID rssId = UUID.randomUUID();
    KnowledgeLibrary rss =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Feed",
            null,
            currentUserId,
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.RSS_FEED,
            null,
            "https://example.org/feed.xml",
            null,
            null,
            false);
    when(libraryRepository.findById(rssId)).thenReturn(Optional.of(rss));
    Mockito.reset(libraryAccessService);
    assertThatThrownBy(
            () ->
                service.listS3Buckets(
                    new S3BucketListingRequest(
                        URI.create("https://example.org"), null, null, null, null, true, rssId),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("keine S3-Bibliothek");
    verify(s3ConnectionService, Mockito.never())
        .listBuckets(anyString(), any(), anyString(), anyBoolean(), any(), anyBoolean());
  }

  @Test
  void aTestWithoutSettingsAndWithoutALibraryReachesTheServiceWithNull() throws Exception {
    when(s3ConnectionService.probe(anyString(), isNull(), anyString(), anyBoolean(), isNull()))
        .thenThrow(new ValidationException("s3Settings sind für den Verbindungstest erforderlich"));

    assertThatThrownBy(
            () ->
                service.test(
                    sourceConnectionTest()
                        .sourceType(DocumentSourceType.S3)
                        .sourceUrl(URI.create("https://s3.example.org"))
                        .sourceCredentials("ak:sk")
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("s3Settings");
  }
}
