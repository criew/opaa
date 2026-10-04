package io.opaa.library;

import io.opaa.api.types.AssetRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.connection.ConnectorReleaseService;
import io.opaa.connection.LibraryConnectionService;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectorLockService;
import io.opaa.connection.profile.EffectiveSourceSettings;
import io.opaa.connection.profile.SourceDraft;
import io.opaa.indexing.source.ConnectorChecks;
import io.opaa.indexing.source.SourceBrowser;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceCredentialsException;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.knowledge.SourceType;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Tests a source configuration <em>before</em> a library is created or saved, and lists what a
 * source offers for selection. The configuration probed is the draft as {@link
 * EffectiveSourceSettings#ofDraft} composes it - with the defaults, proxy, TLS switch and sign-in
 * of the chosen profile, and the stored secret only where it may follow the address; the probe
 * itself is the connector's ({@link SourceConnector#testConnection}, {@link SourceBrowser}).
 *
 * <p>Without a {@code libraryId} a probe needs the connector release of its type or profile, as
 * creating the library would. With one it needs {@link AssetRole#MANAGER} on that library; another
 * profile than the library's needs its release too and is probed as a draft, so neither the
 * library's lock nor its missing profile stands in the way of repairing it. {@code
 * RateLimitConfiguration} caps both endpoints; no response reveals more than a count.
 */
@Service
public class SourceConnectionTestService {

  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService libraryAccessService;
  private final SourceConnectorRegistry connectors;
  private final ConnectorReleaseService connectorRelease;
  private final LibraryConnectionService libraryConnections;
  private final EffectiveSourceSettings drafts;

  public SourceConnectionTestService(
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService libraryAccessService,
      SourceConnectorRegistry connectors,
      ConnectorReleaseService connectorRelease,
      LibraryConnectionService libraryConnections,
      EffectiveSourceSettings drafts) {
    this.libraryRepository = libraryRepository;
    this.libraryAccessService = libraryAccessService;
    this.connectors = connectors;
    this.connectorRelease = connectorRelease;
    this.libraryConnections = libraryConnections;
    this.drafts = drafts;
  }

  /**
   * Probes the request's configuration. An upload type, which its connector refuses, needs a
   * release in any scope.
   */
  public SourceConnectionTestResult test(SourceConnectionTest request, CurrentUser caller) {
    SourceType sourceType = request.sourceType();
    if (sourceType == null) {
      throw new ValidationException("sourceType ist erforderlich");
    }
    SourceConnector connector = connectors.connector(sourceType);
    String url = request.sourceUrl() == null ? null : request.sourceUrl().toString();
    Target target =
        request.libraryId() == null && connector.descriptor().uploads()
            ? anyRelease(caller, url)
            : target(
                caller,
                sourceType,
                request.libraryId(),
                request.connectionProfileId(),
                url,
                () -> "sourceType passt nicht zum gespeicherten Quellentyp dieser Bibliothek");
    SourceDraft draft =
        SourceDraft.ofLibrary(
            sourceType,
            target.profileId(),
            request.libraryId(),
            new SourceSettings(
                request.sourcePath(),
                connector.normalizeSourceUrl(target.url()),
                request.sourceProxy(),
                request.sourceCredentials(),
                Boolean.TRUE.equals(request.sourceInsecureSsl()),
                request.connectorSettings() == null
                    ? null
                    : connector.readSettings(request.connectorSettings())));
    SourceSettings settings;
    try {
      settings = drafts.ofDraft(draft);
    } catch (SourceCredentialsException e) {
      return ConnectorChecks.unreachable(e.getMessage());
    }
    return connector.testConnection(settings, drafts.storedOf(draft));
  }

  /**
   * What a source of the request's type offers for selection before it is saved - the buckets of an
   * S3 key, the spaces of a Confluence token - behind the very same bar and draft as {@link #test}.
   */
  public SourceListing browse(SourceBrowseRequest request, CurrentUser caller) {
    String url = request.sourceUrl() == null ? null : request.sourceUrl().toString();
    // towards a new library the release is checked before anything about the type is said
    Target target =
        request.libraryId() == null
            ? target(caller, request.sourceType(), null, request.connectionProfileId(), url, null)
            : null;
    SourceBrowser browser =
        connectors
            .browser(request.sourceType())
            .orElseThrow(
                () ->
                    new ValidationException(
                        "Für sourceType " + request.sourceType() + " gibt es keine Auflistung"));
    SourceConnector connector = connectors.connector(request.sourceType());
    if (target == null) {
      target =
          target(
              caller,
              request.sourceType(),
              request.libraryId(),
              request.connectionProfileId(),
              url,
              browser::otherTypeMessage);
    }
    String sourceUrl = connector.normalizeSourceUrl(target.url());
    if (sourceUrl == null) {
      throw new ValidationException("sourceUrl ist erforderlich");
    }
    SourceDraft draft =
        SourceDraft.ofLibrary(
            request.sourceType(),
            target.profileId(),
            request.libraryId(),
            new SourceSettings(
                null,
                sourceUrl,
                request.sourceProxy(),
                request.sourceCredentials(),
                Boolean.TRUE.equals(request.sourceInsecureSsl()),
                request.query()));
    SourceSettings settings;
    try {
      settings = drafts.ofDraft(draft);
    } catch (SourceCredentialsException e) {
      return new SourceListing(false, List.of(), e.getMessage());
    }
    return browser.browse(new SourceBrowser.Query(settings, drafts.storedOf(draft)));
  }

  /** The profile a probe runs through ({@code null}: the library's or none) and its address. */
  private record Target(UUID profileId, String url) {}

  private Target anyRelease(CurrentUser caller, String url) {
    connectorRelease.requireAnyRelease(caller);
    return new Target(null, url);
  }

  /**
   * The bar of a probe and where it goes: towards a new library the release of its type or profile;
   * on a stored library {@code MANAGER} and, for another profile, that one's release - the
   * library's own lock counts only while it stays on its profile.
   */
  private Target target(
      CurrentUser caller,
      SourceType sourceType,
      UUID libraryId,
      UUID profileId,
      String url,
      Supplier<String> otherType) {
    if (libraryId == null) {
      connectorRelease.requireCreatable(caller, sourceType, profileId);
      return new Target(
          profileId,
          profileId == null
              ? url
              : libraryConnections.addressForNewLibrary(profileId, sourceType, url));
    }
    KnowledgeLibrary library = requireManagedLibrary(libraryId, caller);
    UUID current =
        libraryConnections
            .connectionOf(libraryId)
            .map(LibraryConnectionService.LibraryConnectionView::profile)
            .map(ConnectionProfile::getId)
            .orElse(null);
    boolean staysOnItsProfile = profileId == null || profileId.equals(current);
    if (staysOnItsProfile) {
      requireUnlocked(library);
    }
    if (!sourceType.equals(library.getSourceType())) {
      throw new ValidationException(otherType.get());
    }
    if (staysOnItsProfile) {
      String normalized = connectors.connector(sourceType).normalizeSourceUrl(url);
      if (normalized != null) {
        libraryConnections.requireAddressAllowed(library, normalized);
      }
      return new Target(null, url);
    }
    connectorRelease.requireCreatable(caller, sourceType, profileId);
    return new Target(
        profileId, libraryConnections.addressForNewLibrary(profileId, sourceType, url));
  }

  /** An existing library's locked source is not reached, like its original (409). */
  private void requireUnlocked(KnowledgeLibrary library) {
    libraryConnections
        .lockOf(library)
        .ifPresent(
            block -> {
              throw new ConflictException(block.notice(), ConnectorLockService.SOURCE_LOCKED);
            });
  }

  /**
   * Resolves {@code libraryId} within the caller's organization and requires {@link
   * AssetRole#MANAGER} on it - 404 when it does not exist there or the caller holds no role on it,
   * 403 below {@code MANAGER}. A system administrator passes as on the save the probe precedes.
   */
  private KnowledgeLibrary requireManagedLibrary(UUID libraryId, CurrentUser caller) {
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .filter(l -> l.getOrganizationId().equals(caller.organizationId()))
            .orElseThrow(() -> new NotFoundException("Bibliothek nicht gefunden"));
    libraryAccessService.requireRole(
        library, caller.id(), caller.isSystemAdmin(), AssetRole.MANAGER);
    return library;
  }
}
