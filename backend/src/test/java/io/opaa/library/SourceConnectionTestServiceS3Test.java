package io.opaa.library;

import static io.opaa.library.SourceConnectionTestBuilder.sourceConnectionTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.connection.ConnectorReleaseService;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.TestSourceConnectors;
import io.opaa.indexing.source.s3.S3BucketListResult;
import io.opaa.indexing.source.s3.S3ConnectionService;
import io.opaa.indexing.source.s3.S3Scope;
import io.opaa.indexing.source.s3.S3ScopeCheck;
import io.opaa.indexing.source.s3.S3SourceSettings;
import io.opaa.indexing.source.s3.S3TestSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.SourceTypes;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The S3 branch of {@link SourceConnectionTestService} (#1376): the effective request handed to
 * {@link S3ConnectionService}, the stored fallback for credentials <em>and</em> the scopes on the
 * same origin, and the permission bar and type check of the bucket listing.
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
  private ConnectorReleaseService connectorRelease;
  private SourceConnectionTestService service;
  private UUID currentUserId;
  private UUID organizationId;
  private CurrentUser caller;

  @BeforeEach
  void setUp() {
    libraryRepository = mock(KnowledgeLibraryRepository.class, CALLS_REAL_METHODS);
    // The draft settings read the library by id, the service by id and organization: one row.
    when(libraryRepository.findByIdAndOrganizationId(any(), any()))
        .thenAnswer(
            call ->
                libraryRepository
                    .findById(call.getArgument(0))
                    .filter(found -> found.getOrganizationId().equals(call.getArgument(1))));
    libraryAccessService = mock(LibraryAccessService.class);
    s3ConnectionService = mock(S3ConnectionService.class);
    connectorRelease = mock(ConnectorReleaseService.class);
    currentUserId = UUID.randomUUID();
    organizationId = UUID.randomUUID();
    caller = CurrentUser.of(currentUserId, organizationId, SystemRole.USER, "Caller");
    service =
        SourceConnectionTestServices.over(
            libraryRepository,
            libraryAccessService,
            TestSourceConnectors.connectors().s3ConnectionService(s3ConnectionService).registry(),
            connectorRelease,
            new ServiceAccountTokens(TargetAddressValidator.disabled(), Clock.systemUTC()));
  }

  private KnowledgeLibrary s3Library(UUID libraryId, String url) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Protokolle",
            null,
            currentUserId,
            SourceTypes.S3,
            null,
            url,
            "AKIASTORED:stored-secret");
    S3TestSettings.configure(library, STORED_SETTINGS);
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    return library;
  }

  private static S3ConnectionService.Probe ok() {
    return new S3ConnectionService.Probe(true, "ok", true, List.of(), 0L);
  }

  @Test
  void connectionTestDelegatesTheEffectiveRequestAndCarriesTheScopeFindings() throws Exception {
    S3ScopeCheck check = new S3ScopeCheck("dokumente", "", true, true, true, 3, false, null);
    when(s3ConnectionService.probe("https://s3.example.org", "ak:sk", SETTINGS))
        .thenReturn(new S3ConnectionService.Probe(true, "erreichbar", true, List.of(check), 3L));

    SourceConnectionTestResult result =
        service.test(
            sourceConnectionTest()
                .sourceType(SourceTypes.S3)
                .sourceUrl(URI.create("https://s3.example.org"))
                .sourceCredentials("ak:sk")
                .s3Settings(SETTINGS)
                .build(),
            caller);

    assertThat(result.reachable()).isTrue();
    assertThat(result.documentCount()).isEqualTo(3);
    assertThat(result.credentialsVerified()).isTrue();
    assertThat(TestConnectorSettings.edition(result)).isNull();
    assertThat(TestConnectorSettings.scopes(result)).containsExactly((Object) check.toJson());
  }

  @Test
  void connectionTestRejectsAPathAndRequiresTheEndpoint() {
    assertThatThrownBy(
            () ->
                service.test(
                    sourceConnectionTest()
                        .sourceType(SourceTypes.S3)
                        .sourcePath("/srv/docs")
                        .sourceUrl(URI.create("https://s3.example.org"))
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourcePath");
    assertThatThrownBy(
            () -> service.test(sourceConnectionTest().sourceType(SourceTypes.S3).build(), caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceUrl");
  }

  @Test
  void connectionTestFallsBackToStoredCredentialsAndScopesOnTheSameOrigin() throws Exception {
    UUID libraryId = UUID.randomUUID();
    s3Library(libraryId, "https://s3.example.org");
    when(s3ConnectionService.probe(anyString(), anyString(), any())).thenReturn(ok());

    service.test(
        sourceConnectionTest()
            .sourceType(SourceTypes.S3)
            .sourceUrl(URI.create("https://s3.example.org/"))
            .libraryId(libraryId)
            .build(),
        caller);

    // #617: the stored credential is replayed on the same origin; the stored scopes stand in
    // because the request named none
    verify(s3ConnectionService)
        .probe("https://s3.example.org/", "AKIASTORED:stored-secret", STORED_SETTINGS);
    verify(libraryAccessService)
        .requireRole(any(), eq(currentUserId), eq(false), eq(AssetRole.MANAGER));
  }

  @Test
  void connectionTestDoesNotReplayTheStoredKeyForAnotherOriginButStillUsesTheStoredScopes()
      throws Exception {
    UUID libraryId = UUID.randomUUID();
    s3Library(libraryId, "https://s3.example.org");
    when(s3ConnectionService.probe(anyString(), any(), any())).thenReturn(ok());

    service.test(
        sourceConnectionTest()
            .sourceType(SourceTypes.S3)
            .sourceUrl(URI.create("https://s3.other.example"))
            .libraryId(libraryId)
            .build(),
        caller);

    verify(s3ConnectionService).probe("https://s3.other.example", null, STORED_SETTINGS);
  }

  /** #1856: the same connector release {@code createLibrary} needs, checked ahead of validation. */
  @Test
  void bucketListingWithoutLibraryIdRequiresTheConnectorCapability() {
    Mockito.doThrow(new AccessDeniedException("Ihnen fehlt das Anlegerecht", "CAPABILITY_REQUIRED"))
        .when(connectorRelease)
        .requireCreatable(caller, SourceTypes.S3, null);

    assertThatThrownBy(
            () ->
                service.browse(
                    new SourceBrowseRequest(
                        SourceTypes.S3,
                        URI.create("https://s3.example.org"),
                        "ak:sk",
                        TestConnectorSettings.s3Query(null, true),
                        null),
                    caller))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void bucketListingRequiresCredentialsWithoutALibraryToFallBackOn() {
    assertThatThrownBy(
            () ->
                service.browse(
                    new SourceBrowseRequest(
                        SourceTypes.S3,
                        URI.create("https://s3.example.org"),
                        null,
                        TestConnectorSettings.s3Query(null, true),
                        null),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceCredentials");
    assertThatThrownBy(
            () ->
                service.browse(
                    new SourceBrowseRequest(
                        SourceTypes.S3,
                        null,
                        "ak:sk",
                        TestConnectorSettings.s3Query(null, true),
                        null),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceUrl");
  }

  @Test
  void bucketListingFallsBackToStoredCredentials() throws Exception {
    UUID libraryId = UUID.randomUUID();
    s3Library(libraryId, "https://s3.example.org");
    when(s3ConnectionService.listBuckets(anyString(), anyString(), any(), anyBoolean()))
        .thenReturn(new S3BucketListResult(true, List.of("protokolle"), null));

    SourceListing result =
        service.browse(
            new SourceBrowseRequest(
                SourceTypes.S3,
                URI.create("https://s3.example.org"),
                null,
                TestConnectorSettings.s3Query("eu-west-1", false),
                libraryId),
            caller);

    assertThat(result.entries()).extracting(SourceListing.Entry::key).containsExactly("protokolle");
    verify(s3ConnectionService)
        .listBuckets("https://s3.example.org", "AKIASTORED:stored-secret", "eu-west-1", false);

    // without region and style in the request, the stored settings sign the listing
    service.browse(
        new SourceBrowseRequest(
            SourceTypes.S3, URI.create("https://s3.example.org"), null, null, libraryId),
        caller);
    verify(s3ConnectionService)
        .listBuckets("https://s3.example.org", "AKIASTORED:stored-secret", "eu-central-1", true);
  }

  @Test
  void anInterruptedProbeIsReportedNotThrown() throws Exception {
    when(s3ConnectionService.probe(anyString(), anyString(), any()))
        .thenThrow(new InterruptedException());

    SourceConnectionTestResult result =
        service.test(
            sourceConnectionTest()
                .sourceType(SourceTypes.S3)
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
                service.browse(
                    new SourceBrowseRequest(
                        SourceTypes.S3,
                        URI.create("https://s3.example.org"),
                        null,
                        TestConnectorSettings.s3Query(null, true),
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
            SourceTypes.RSS_FEED,
            null,
            "https://example.org/feed.xml",
            null);
    when(libraryRepository.findById(rssId)).thenReturn(Optional.of(rss));
    Mockito.reset(libraryAccessService);
    assertThatThrownBy(
            () ->
                service.browse(
                    new SourceBrowseRequest(
                        SourceTypes.S3,
                        URI.create("https://example.org"),
                        null,
                        TestConnectorSettings.s3Query(null, true),
                        rssId),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("keine S3-Bibliothek");
    verify(s3ConnectionService, Mockito.never())
        .listBuckets(anyString(), anyString(), any(), anyBoolean());
  }

  @Test
  void aTestWithoutSettingsAndWithoutALibraryReachesTheServiceWithNull() throws Exception {
    when(s3ConnectionService.probe(anyString(), anyString(), isNull()))
        .thenThrow(
            new ValidationException("sourceSettings sind für den Verbindungstest erforderlich"));

    assertThatThrownBy(
            () ->
                service.test(
                    sourceConnectionTest()
                        .sourceType(SourceTypes.S3)
                        .sourceUrl(URI.create("https://s3.example.org"))
                        .sourceCredentials("ak:sk")
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceSettings");
  }
}
