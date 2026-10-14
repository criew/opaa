package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.connection.ConnectorReleaseService;
import io.opaa.indexing.credentials.ServiceAccountTokens;
import io.opaa.indexing.registry.TestSourceConnectors;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.confluence.ConfluenceConnectionService;
import io.opaa.indexing.source.confluence.ConfluenceEdition;
import io.opaa.indexing.source.confluence.ConfluenceSpace;
import io.opaa.indexing.source.confluence.ConfluenceSpaceSelection;
import io.opaa.indexing.source.confluence.ConfluenceTestSettings;
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

/**
 * The Confluence paths of {@link SourceConnectionTestService} (#1134): the connection test
 * delegates to {@link ConfluenceConnectionService} with the effective (possibly stored)
 * credentials, and the space listing applies the same MANAGER bar and same-origin fallback as the
 * test - through the same code, so this class pins the contract of that sharing.
 */
class SourceConnectionTestServiceConfluenceTest {

  private KnowledgeLibraryRepository libraryRepository;
  private LibraryAccessService libraryAccessService;
  private ConfluenceConnectionService confluenceConnectionService;
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
    confluenceConnectionService = mock(ConfluenceConnectionService.class);
    connectorRelease = mock(ConnectorReleaseService.class);
    currentUserId = UUID.randomUUID();
    organizationId = UUID.randomUUID();
    caller = CurrentUser.of(currentUserId, organizationId, SystemRole.USER, "Caller");
    service =
        SourceConnectionTestServices.over(
            libraryRepository,
            libraryAccessService,
            TestSourceConnectors.connectors()
                .confluenceConnectionService(confluenceConnectionService)
                .registry(),
            connectorRelease,
            new ServiceAccountTokens(TargetAddressValidator.disabled(), Clock.systemUTC()));
  }

  private KnowledgeLibrary confluenceLibrary(UUID libraryId, String url) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Wiki",
            null,
            currentUserId,
            SourceTypes.CONFLUENCE,
            null,
            url,
            "stored-pat");
    ConfluenceTestSettings.configure(
        library, ConfluenceEdition.DATA_CENTER, List.of(new ConfluenceSpaceSelection("ENG", null)));
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    return library;
  }

  @Test
  void connectionTestDelegatesTheEffectiveRequestToTheConfluenceService() throws Exception {
    when(confluenceConnectionService.probe(
            "https://wiki.example.org", "pat", ConfluenceEdition.DATA_CENTER))
        .thenReturn(
            new ConfluenceConnectionService.Probe(
                true, "Zugangsdaten gültig.", ConfluenceEdition.DATA_CENTER, true, null));

    SourceConnectionTestResult result =
        service.test(
            new SourceConnectionTest(
                SourceTypes.CONFLUENCE,
                null,
                URI.create("https://wiki.example.org"),
                "pat",
                null,
                TestConnectorSettings.of(ConfluenceEdition.DATA_CENTER, null)),
            caller);

    assertThat(result.reachable()).isTrue();
    assertThat(TestConnectorSettings.edition(result)).isEqualTo(ConfluenceEdition.DATA_CENTER);
    assertThat(result.credentialsVerified()).isTrue();
    assertThat(result.documentCount()).isNull();
  }

  @Test
  void connectionTestRejectsAPathForConfluence() {
    assertThatThrownBy(
            () ->
                service.test(
                    new SourceConnectionTest(
                        SourceTypes.CONFLUENCE,
                        "/srv/docs",
                        URI.create("https://wiki.example.org"),
                        null,
                        null,
                        null),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourcePath");
  }

  /** #1856: the same connector release {@code createLibrary} needs, checked ahead of validation. */
  @Test
  void spaceListingWithoutLibraryIdRequiresTheConnectorCapability() {
    doThrow(new AccessDeniedException("Ihnen fehlt das Anlegerecht", "CAPABILITY_REQUIRED"))
        .when(connectorRelease)
        .requireCreatable(caller, SourceTypes.CONFLUENCE, null);

    assertThatThrownBy(
            () ->
                service.browse(
                    new SourceBrowseRequest(
                        SourceTypes.CONFLUENCE,
                        URI.create("https://wiki.example.org"),
                        "pat",
                        TestConnectorSettings.of(ConfluenceEdition.DATA_CENTER, null),
                        null),
                    caller))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void spaceListingRequiresCredentialsWithoutALibraryToFallBackOn() {
    assertThatThrownBy(
            () ->
                service.browse(
                    new SourceBrowseRequest(
                        SourceTypes.CONFLUENCE,
                        URI.create("https://wiki.example.org"),
                        null,
                        TestConnectorSettings.of(ConfluenceEdition.DATA_CENTER, null),
                        null),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceCredentials");
  }

  @Test
  void spaceListingFallsBackToStoredCredentialsAndTlsOnTheSameOrigin() throws Exception {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = confluenceLibrary(libraryId, "https://wiki.example.org/confluence");
    when(libraryAccessService.requireRole(library, currentUserId, false, AssetRole.MANAGER))
        .thenReturn(AssetRole.MANAGER);
    when(confluenceConnectionService.listSpaces(anyString(), any(), anyString()))
        .thenReturn(List.of(new ConfluenceSpace("1", "ENG", "Engineering")));

    // same origin, other path
    SourceListing spaces =
        service.browse(
            new SourceBrowseRequest(
                SourceTypes.CONFLUENCE,
                URI.create("https://wiki.example.org/other"),
                null,
                TestConnectorSettings.of(ConfluenceEdition.DATA_CENTER, null),
                libraryId),
            caller);

    assertThat(spaces.entries()).extracting(SourceListing.Entry::key).containsExactly("ENG");
    // stored token - never the caller's (#617 rule shared with the connection test)
    verify(confluenceConnectionService)
        .listSpaces(
            eq("https://wiki.example.org/other"),
            eq(ConfluenceEdition.DATA_CENTER),
            eq("stored-pat"));
  }

  @Test
  void spaceListingDoesNotFallBackForAnotherOrigin() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = confluenceLibrary(libraryId, "https://wiki.example.org/confluence");
    when(libraryAccessService.requireRole(library, currentUserId, false, AssetRole.MANAGER))
        .thenReturn(AssetRole.MANAGER);

    assertThatThrownBy(
            () ->
                service.browse(
                    new SourceBrowseRequest(
                        SourceTypes.CONFLUENCE,
                        URI.create("https://other.example.org/confluence"),
                        null,
                        TestConnectorSettings.of(ConfluenceEdition.DATA_CENTER, null),
                        libraryId),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceCredentials");
  }

  @Test
  void spaceListingRequiresManagerAndAConfluenceLibrary() throws Exception {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = confluenceLibrary(libraryId, "https://wiki.example.org");
    when(libraryAccessService.requireRole(library, currentUserId, false, AssetRole.MANAGER))
        .thenThrow(new AccessDeniedException("Kein Zugriff auf diese Bibliothek"));
    SourceBrowseRequest listing =
        new SourceBrowseRequest(
            SourceTypes.CONFLUENCE,
            URI.create("https://wiki.example.org"),
            null,
            TestConnectorSettings.of(ConfluenceEdition.DATA_CENTER, null),
            libraryId);

    assertThatThrownBy(() -> service.browse(listing, caller))
        .isInstanceOf(AccessDeniedException.class);
    verify(confluenceConnectionService, never()).listSpaces(anyString(), any(), anyString());

    UUID rssId = UUID.randomUUID();
    KnowledgeLibrary rss =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Feed",
            null,
            currentUserId,
            SourceTypes.RSS_FEED,
            null,
            "https://wiki.example.org/feed.xml",
            "user:pw");
    when(libraryRepository.findById(rssId)).thenReturn(Optional.of(rss));
    when(libraryAccessService.requireRole(rss, currentUserId, false, AssetRole.MANAGER))
        .thenReturn(AssetRole.MANAGER);
    assertThatThrownBy(
            () ->
                service.browse(
                    new SourceBrowseRequest(
                        SourceTypes.CONFLUENCE,
                        URI.create("https://wiki.example.org"),
                        null,
                        TestConnectorSettings.of(ConfluenceEdition.DATA_CENTER, null),
                        rssId),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("keine Confluence-Bibliothek");
    verify(confluenceConnectionService, never()).listSpaces(anyString(), any(), anyString());
  }
}
