package io.opaa.library;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.Capability;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.SourceBrowser;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.knowledge.sourcesettings.S3SourceSettings;
import io.opaa.permission.CapabilityService;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Tests a source configuration <em>before</em> a library is created (#514) or saved, and lists what
 * a source offers for selection - the permission bar and the stored-credentials fallback here, the
 * probe itself in the library type's {@link SourceConnector} ({@link
 * SourceConnector#testConnection}, {@link SourceBrowser}), reached through the {@link
 * SourceConnectorRegistry}.
 *
 * <p><b>Security (#514 acceptance criteria, PR #537 review finding 3; capability bar #1856).</b>
 * Without a {@code libraryId}, a probe or a listing needs {@link
 * Capability#CREATE_CONNECTOR_LIBRARY} (ADR-0036, Entscheidung 5), the same right {@code
 * KnowledgeLibraryService#createLibrary} requires for the connector library the probe is a step
 * towards - before #1856, this endpoint let any authenticated caller probe arbitrary server-local
 * paths and arbitrary URLs regardless of whether that caller could ever create the library the
 * probe served. Every connector bounds its probe in time and applies the target validation and path
 * allowlist exactly as a run would; {@code RateLimitConfiguration} additionally caps this endpoint
 * per IP and globally. No response reveals more about a source's contents than a count.
 *
 * <p><b>Testing an existing library's stored quellkonfiguration (#544).</b> {@link
 * SourceConnectionTest#libraryId()} lets {@code EditLibrarySourceDialog} test a password-protected
 * source without forcing the caller to re-type a credential the library already has stored -
 * reachable only with at least {@link AssetRole#MANAGER} on that library (see {@link
 * #requireManagedLibrary}), via {@link LibraryAccessService#requireRole} (#436), the same
 * not-found/forbidden split every other library-scoped endpoint now uses. The library's own {@code
 * sourceType} must match this request's (otherwise 400 - #544 acceptance criterion), and a missing
 * {@code sourceCredentials} falls back to the library's stored one only when {@code sourceUrl}
 * still names the same origin as the library's own stored {@code sourceUrl} - the identical {@link
 * SourceOriginMatcher} rule {@code KnowledgeLibraryService} already applies when saving, so a
 * caller pointed at a different host cannot silently reuse a credential it never entered.
 *
 * <p><b>The origin check above bounds the target, not the path (#617).</b> Whenever the credentials
 * fallback fires, {@code sourceProxy}/{@code sourceInsecureSsl} are forced to the library's own
 * stored values too - {@link #withStoredCredentialsIfOmitted}'s own Javadoc has the full reasoning.
 * Without this, a caller who does not know the stored credential could still route it through a
 * proxy of their own choosing (or disable certificate validation) on an otherwise same-origin
 * request and read it back over a connection they control.
 */
@Service
public class SourceConnectionTestService {

  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService libraryAccessService;
  private final SourceConnectorRegistry connectors;
  private final CapabilityService capabilityService;

  public SourceConnectionTestService(
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService libraryAccessService,
      SourceConnectorRegistry connectors,
      CapabilityService capabilityService) {
    this.libraryRepository = libraryRepository;
    this.libraryAccessService = libraryAccessService;
    this.connectors = connectors;
    this.capabilityService = capabilityService;
  }

  /**
   * Without a {@code libraryId}, this is a step towards creating a connector library and needs
   * {@link Capability#CREATE_CONNECTOR_LIBRARY} (ADR-0036, Entscheidung 5) - the same right {@code
   * KnowledgeLibraryService#createLibrary} requires for the library the probe serves (#1856). With
   * {@code libraryId} set (#544), the caller instead needs {@link AssetRole#MANAGER} on that
   * library, checked by {@link #requireManagedLibrary} below - creating a connector library already
   * required the capability, so re-demanding it here would only block a caller who already holds
   * {@code MANAGER} without adding a boundary.
   */
  public SourceConnectionTestResult test(SourceConnectionTest request, CurrentUser caller) {
    if (request.libraryId() == null) {
      capabilityService.requireCapability(caller, Capability.CREATE_CONNECTOR_LIBRARY);
    }
    DocumentSourceType sourceType = request.sourceType();
    if (sourceType == null) {
      throw new ValidationException("sourceType ist erforderlich");
    }
    SourceSettings settings = settingsOf(request);
    if (request.libraryId() != null) {
      KnowledgeLibrary library = requireManagedLibrary(request.libraryId(), caller);
      if (library.getSourceType() != sourceType) {
        throw new ValidationException(
            "sourceType passt nicht zum gespeicherten Quellentyp dieser Bibliothek");
      }
      settings = withStoredCredentialsIfOmitted(settings, library);
    }
    return connectors.connector(sourceType).testConnection(settings);
  }

  /**
   * The buckets an S3 key may see (ADR-0027, #1376), for the wizard's scope entry - the same
   * permission bar, stored-credentials fallback and proxy/TLS forcing as {@link #test}.
   */
  public SourceListing listS3Buckets(S3BucketListingRequest request, CurrentUser caller) {
    return browse(
        SourceBrowser.Kind.BUCKETS,
        request.libraryId(),
        new SourceSettings(
            null,
            request.sourceUrl() == null ? null : request.sourceUrl().toString(),
            request.sourceProxy(),
            request.sourceCredentials(),
            Boolean.TRUE.equals(request.sourceInsecureSsl()),
            null,
            null,
            null,
            null),
        request.region(),
        request.pathStyle(),
        caller);
  }

  /**
   * The spaces a Confluence token may read (ADR-0023), for the wizard's selection - the same
   * permission bar, stored-credentials fallback and proxy/TLS forcing as {@link #test}.
   */
  public SourceListing listConfluenceSpaces(ConfluenceSpaceListing request, CurrentUser caller) {
    return browse(
        SourceBrowser.Kind.SPACES,
        request.libraryId(),
        new SourceSettings(
            null,
            request.sourceUrl() == null ? null : request.sourceUrl().toString(),
            request.sourceProxy(),
            request.sourceCredentials(),
            Boolean.TRUE.equals(request.sourceInsecureSsl()),
            request.confluenceEdition(),
            null,
            null,
            null),
        null,
        null,
        caller);
  }

  /**
   * A listing through the connector offering {@code kind}, behind the very same permission bar and
   * {@link #withStoredCredentialsIfOmitted} as {@link #test}, so the paths cannot drift apart.
   * Without {@code libraryId}, the {@link Capability#CREATE_CONNECTOR_LIBRARY} bar applies (#1856).
   */
  private SourceListing browse(
      SourceBrowser.Kind kind,
      UUID libraryId,
      SourceSettings requested,
      String region,
      Boolean pathStyle,
      CurrentUser caller) {
    if (libraryId == null) {
      capabilityService.requireCapability(caller, Capability.CREATE_CONNECTOR_LIBRARY);
    }
    if (requested.sourceUrl() == null) {
      throw new ValidationException("sourceUrl ist erforderlich");
    }
    SourceConnector connector = connectors.browser(kind);
    SourceBrowser browser = (SourceBrowser) connector;
    SourceSettings settings = requested;
    if (libraryId != null) {
      KnowledgeLibrary library = requireManagedLibrary(libraryId, caller);
      if (library.getSourceType() != connector.descriptor().type()) {
        throw new ValidationException(browser.otherTypeMessage());
      }
      settings = withStoredCredentialsIfOmitted(settings, library);
    }
    return browser.browse(new SourceBrowser.Query(settings, region, pathStyle));
  }

  private static SourceSettings settingsOf(SourceConnectionTest request) {
    return new SourceSettings(
        request.sourcePath(),
        request.sourceUrl() == null ? null : request.sourceUrl().toString(),
        request.sourceProxy(),
        request.sourceCredentials(),
        Boolean.TRUE.equals(request.sourceInsecureSsl()),
        request.confluenceEdition(),
        null,
        null,
        request.s3Settings());
  }

  /**
   * Resolves {@code libraryId} and enforces both the organization boundary and the {@link
   * AssetRole#MANAGER} bar (#544) via {@link LibraryAccessService#requireRole} (#436) - 404 if the
   * library does not exist, belongs to another organization, or the caller holds no role on it at
   * all (indistinguishable from "does not exist" - the org boundary/lack of any grant must not leak
   * even that much), 403 if the caller's role is below MANAGER, the same distinction every other
   * library-scoped endpoint now makes (e.g. {@code KnowledgeLibraryService#updateLibrary}, {@code
   * DocumentIndexingService#requireEditableLibrary}).
   *
   * <p>{@code systemAdmin} is passed through to {@code requireRole} exactly like {@code
   * KnowledgeLibraryService#updateLibrary} passes it (#615 review, finding 3) - the save this test
   * precedes already lets a {@code SYSTEM_ADMIN} through without a grant, so hard-coding {@code
   * false} here (as {@code DocumentIndexingService#requireEditableLibrary} deliberately does for
   * indexing runs, ADR-0018 Entscheidung 2) would make a system admin's own "Verbindung testen"
   * click fail with 404 right before a save that would have succeeded.
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

  /**
   * Fills in the library's own stored {@code sourceCredentials} when the request carries none and
   * {@code sourceUrl} still names the same origin as the library's stored one (#544, same rule as
   * {@code KnowledgeLibraryService} applies when saving) - a no-op for a library without an
   * address.
   *
   * <p><b>{@code sourceProxy}/{@code sourceInsecureSsl} are forced to the library's own stored
   * values whenever this fallback fires (#617).</b> The origin check above only bounds the
   * <em>target</em> the stored credential may be tested against - it says nothing about the
   * <em>path</em> the request travels to get there. Before this fix, a caller with {@code
   * AssetRole#MANAGER} on the library (enough to trigger this fallback, not enough to already know
   * the stored credential) could still set their own {@code sourceProxy}/{@code sourceInsecureSsl}
   * on the very same request - same origin, attacker-controlled proxy, certificate validation
   * disabled - and have the stored Basic-Auth credential replayed straight through a connection
   * they control. Forcing both fields to the library's own stored configuration (rather than
   * rejecting the combination with 400) is the less disruptive of the two options #617 named: a
   * caller who genuinely wants to test through a proxy of their own choosing already has to supply
   * the credential themselves - that combination was never a legitimate use of this fallback to
   * begin with, so nothing a real caller relied on changes.
   *
   * <p>A request without its own {@code s3Settings} probes the library's stored ones (ADR-0027) -
   * settings are not a secret, so unlike the credential they stand in regardless of the origin.
   */
  private static SourceSettings withStoredCredentialsIfOmitted(
      SourceSettings request, KnowledgeLibrary library) {
    S3SourceSettings s3Settings =
        request.s3Settings() == null ? library.getS3Settings() : request.s3Settings();
    boolean fallback =
        blankToNull(request.sourceCredentials()) == null
            && SourceOriginMatcher.sameOrigin(library.getSourceUrl(), request.sourceUrl());
    return new SourceSettings(
        request.sourcePath(),
        request.sourceUrl(),
        fallback ? library.getSourceProxy() : request.sourceProxy(),
        fallback ? library.getSourceCredentials() : request.sourceCredentials(),
        fallback ? library.isSourceInsecureSsl() : request.sourceInsecureSsl(),
        request.confluenceEdition(),
        request.confluenceSpaces(),
        request.confluenceFullSyncIntervalDays(),
        s3Settings);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
