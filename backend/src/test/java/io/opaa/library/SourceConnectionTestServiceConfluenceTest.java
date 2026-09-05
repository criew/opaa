package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.DocumentService;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.source.confluence.ConfluenceSpace;
import io.opaa.indexing.source.filesystem.FilesystemPathAllowlist;
import io.opaa.indexing.source.rss.RssFeedParser;
import io.opaa.indexing.source.web.AutoindexCrawlerService;
import io.opaa.sourceaccess.SourceRequestPolicy;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Confluence paths of {@link SourceConnectionTestService} (#1134): the connection test
 * delegates to {@link ConfluenceConnectionService} with the effective (possibly stored)
 * credentials, and the space listing applies the same MANAGER bar, same-origin fallback and
 * proxy/TLS forcing as the test - through the same code, so this class pins the contract of that
 * sharing.
 */
class SourceConnectionTestServiceConfluenceTest {

  private KnowledgeLibraryRepository libraryRepository;
  private LibraryAccessService libraryAccessService;
  private ConfluenceConnectionService confluenceConnectionService;
  private SourceConnectionTestService service;
  private UUID currentUserId;
  private UUID organizationId;
  private CurrentUser caller;

  @BeforeEach
  void setUp() {
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    libraryAccessService = mock(LibraryAccessService.class);
    confluenceConnectionService = mock(ConfluenceConnectionService.class);
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
            confluenceConnectionService);
  }

  private KnowledgeLibrary confluenceLibrary(UUID libraryId, String url) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Wiki",
            null,
            currentUserId,
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.CONFLUENCE,
            null,
            url,
            "proxy.stored.example:3128",
            "stored-pat",
            true);
    library.configureConfluence(
        ConfluenceEdition.DATA_CENTER, List.of(new ConfluenceSpaceSelection("ENG", null)));
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    return library;
  }

  @Test
  void connectionTestDelegatesTheEffectiveRequestToTheConfluenceService() throws Exception {
    when(confluenceConnectionService.probe(
            "https://wiki.example.org", null, "pat", false, ConfluenceEdition.DATA_CENTER))
        .thenReturn(
            new ConfluenceConnectionService.Probe(
                true, "Zugangsdaten gültig.", ConfluenceEdition.DATA_CENTER, true, null));

    SourceConnectionTestResult result =
        service.test(
            new SourceConnectionTest(
                DocumentSourceType.CONFLUENCE,
                null,
                URI.create("https://wiki.example.org"),
                null,
                "pat",
                false,
                null,
                ConfluenceEdition.DATA_CENTER),
            caller);

    assertThat(result.reachable()).isTrue();
    assertThat(result.confluenceEdition()).isEqualTo(ConfluenceEdition.DATA_CENTER);
    assertThat(result.credentialsVerified()).isTrue();
    assertThat(result.documentCount()).isNull();
  }

  @Test
  void connectionTestRejectsAPathForConfluence() {
    assertThatThrownBy(
            () ->
                service.test(
                    new SourceConnectionTest(
                        DocumentSourceType.CONFLUENCE,
                        "/srv/docs",
                        URI.create("https://wiki.example.org"),
                        null,
                        null,
                        null,
                        null,
                        null),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourcePath");
  }

  @Test
  void spaceListingRequiresCredentialsWithoutALibraryToFallBackOn() {
    assertThatThrownBy(
            () ->
                service.listConfluenceSpaces(
                    new ConfluenceSpaceListing(
                        URI.create("https://wiki.example.org"),
                        ConfluenceEdition.DATA_CENTER,
                        null,
                        null,
                        null,
                        null),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sourceCredentials");
  }

  @Test
  void spaceListingFallsBackToStoredCredentialsProxyAndTlsOnTheSameOrigin() throws Exception {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = confluenceLibrary(libraryId, "https://wiki.example.org/confluence");
    when(libraryAccessService.requireRole(library, currentUserId, false, AssetRole.MANAGER))
        .thenReturn(AssetRole.MANAGER);
    when(confluenceConnectionService.listSpaces(
            anyString(), any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(List.of(new ConfluenceSpace("1", "ENG", "Engineering")));

    // same origin, other path; the caller offers its own proxy and asks to skip TLS checks
    List<ConfluenceSpace> spaces =
        service.listConfluenceSpaces(
            new ConfluenceSpaceListing(
                URI.create("https://wiki.example.org/other"),
                ConfluenceEdition.DATA_CENTER,
                null,
                "proxy.attacker.example:8080",
                false,
                libraryId),
            caller);

    assertThat(spaces).extracting(ConfluenceSpace::key).containsExactly("ENG");
    // stored token, stored proxy, stored TLS setting - never the caller's (#617 rule shared with
    // the connection test)
    verify(confluenceConnectionService)
        .listSpaces(
            eq("https://wiki.example.org/other"),
            eq(ConfluenceEdition.DATA_CENTER),
            eq("proxy.stored.example:3128"),
            eq("stored-pat"),
            eq(true));
  }

  @Test
  void spaceListingDoesNotFallBackForAnotherOrigin() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library = confluenceLibrary(libraryId, "https://wiki.example.org/confluence");
    when(libraryAccessService.requireRole(library, currentUserId, false, AssetRole.MANAGER))
        .thenReturn(AssetRole.MANAGER);

    assertThatThrownBy(
            () ->
                service.listConfluenceSpaces(
                    new ConfluenceSpaceListing(
                        URI.create("https://other.example.org/confluence"),
                        ConfluenceEdition.DATA_CENTER,
                        null,
                        null,
                        null,
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
    ConfluenceSpaceListing listing =
        new ConfluenceSpaceListing(
            URI.create("https://wiki.example.org"),
            ConfluenceEdition.DATA_CENTER,
            null,
            null,
            null,
            libraryId);

    assertThatThrownBy(() -> service.listConfluenceSpaces(listing, caller))
        .isInstanceOf(AccessDeniedException.class);
    verify(confluenceConnectionService, never())
        .listSpaces(anyString(), any(), any(), anyString(), anyBoolean());

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
            "https://wiki.example.org/feed.xml",
            null,
            "user:pw",
            false);
    when(libraryRepository.findById(rssId)).thenReturn(Optional.of(rss));
    when(libraryAccessService.requireRole(rss, currentUserId, false, AssetRole.MANAGER))
        .thenReturn(AssetRole.MANAGER);
    assertThatThrownBy(
            () ->
                service.listConfluenceSpaces(
                    new ConfluenceSpaceListing(
                        URI.create("https://wiki.example.org"),
                        ConfluenceEdition.DATA_CENTER,
                        null,
                        null,
                        null,
                        rssId),
                    caller))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("keine Confluence-Bibliothek");
    verify(confluenceConnectionService, never())
        .listSpaces(anyString(), any(), isNull(), anyString(), anyBoolean());
  }
}
