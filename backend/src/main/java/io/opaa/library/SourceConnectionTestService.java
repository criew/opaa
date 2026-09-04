package io.opaa.library;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.DocumentService;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.SupportedDocumentFormats;
import io.opaa.indexing.source.confluence.ConfluenceSpace;
import io.opaa.indexing.source.filesystem.FilesystemPathAllowlist;
import io.opaa.indexing.source.rss.RssFeedEntry;
import io.opaa.indexing.source.rss.RssFeedParseException;
import io.opaa.indexing.source.rss.RssFeedParser;
import io.opaa.indexing.source.web.AutoindexCrawlerService;
import io.opaa.indexing.source.web.UrlIndexingExecutor;
import io.opaa.sourceaccess.ProxyAndCredentials;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Tests a source configuration <em>before</em> a library is created (#514) - the same checks {@link
 * KnowledgeLibraryService#createLibrary} and the corresponding {@code
 * io.opaa.indexing.source.SourceIndexingExecutor} would otherwise only surface much later, at the
 * first indexing run: whether a FILESYSTEM directory exists and is readable, whether an
 * HTTP_DIRECTORY page answers under the configured proxy/credentials/certificate settings, whether
 * an RSS_FEED URL serves a parseable feed, and - for CONFLUENCE (ADR-0023) - which edition an
 * address is and whether the credentials fit it ({@link ConfluenceConnectionService}).
 *
 * <p><b>Same building blocks as the real runs, deliberately</b> (issue #514): {@link
 * SourceHttpClientFactory#buildHttpClient} and {@link SourceHttpClientFactory#buildAuthHeader} are
 * the exact methods {@code UrlIndexingExecutor} and {@code RssFeedIndexingExecutor} use, and the
 * response body is bounded exactly the way {@code FeedFetcher#readFeedBody} and {@code
 * BoundedDownloader#readBounded} bound theirs - {@link IndexingProperties.Rss#maxPageSizeBytes()}
 * for the HTTP_DIRECTORY listing page, {@link IndexingProperties.Rss#maxFeedSizeBytes()} for the
 * RSS feed (PR #537 review, finding 2: an unbounded read here let an authenticated caller crash the
 * whole backend with a single request against an endless or multi-gigabyte response). {@code
 * RssFeedIndexingExecutor} applies proxy/credentials to its own feed, detail-page and attachment
 * requests too (#505) - restricted to the feed's own origin for the latter two (PR #642 review,
 * finding 1); this test, having no entries or attachments to consider, always applies them for the
 * one address it was given.
 *
 * <p><b>Security (#514 acceptance criteria, PR #537 review finding 3).</b> This endpoint lets any
 * caller with the right to create a library probe arbitrary server-local paths (FILESYSTEM) and
 * arbitrary URLs (HTTP_DIRECTORY/RSS_FEED) - the same path-enumeration/SSRF surface {@code
 * validateConfigurationForType} already reasons about for creation itself, made cheaper to exploit
 * by being a single synchronous request instead of "create library, trigger indexing, read job
 * status". FILESYSTEM is therefore gated by the identical {@link FilesystemPathAllowlist} check
 * creation applies, before anything on disk is touched; every per-request HTTP timeout here is kept
 * well under {@code buildHttpClient}'s 30s connect timeout so a single caller cannot tie up
 * Tomcat's worker pool for long by requesting many tests against a filtered address at once -
 * {@code RateLimitConfiguration} additionally caps this endpoint per IP and globally, the same way
 * it already does for the indexing trigger. No response ever reveals more about a directory's
 * contents than a count - never a file name, a listing, or an exception's raw text. Target
 * validation for the URL-based types' addresses themselves (blocking internal/private ranges,
 * {@code TargetAddressValidator}) applies to every fetch here exactly as to the indexing run - for
 * CONFLUENCE including the credential-free edition probes and the proxy host - see
 * docs/features/knowledge-sources.md.
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
 * SourceOriginMatcher} rule {@link KnowledgeLibraryService#validateSourceConfigurationForUpdate}
 * already applies when saving, so a caller pointed at a different host cannot silently reuse a
 * credential it never entered.
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

  private static final Logger log = LoggerFactory.getLogger(SourceConnectionTestService.class);

  /** Well under buildHttpClient's 30s connect timeout (PR #537 review, finding 3). */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final DocumentService documentService;
  private final AutoindexCrawlerService crawlerService;
  private final RssFeedParser rssFeedParser;
  private final FilesystemPathAllowlist filesystemAllowlist;
  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService libraryAccessService;
  private final String rssUserAgent;
  private final long maxPageSizeBytes;
  private final long maxFeedSizeBytes;
  private final int maxFeedEntries;
  private final TargetAddressValidator targetAddressValidator;
  private final ConfluenceConnectionService confluenceConnectionService;

  public SourceConnectionTestService(
      DocumentService documentService,
      AutoindexCrawlerService crawlerService,
      RssFeedParser rssFeedParser,
      FilesystemPathAllowlist filesystemAllowlist,
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService libraryAccessService,
      IndexingProperties properties,
      TargetAddressValidator targetAddressValidator,
      ConfluenceConnectionService confluenceConnectionService) {
    this.documentService = documentService;
    this.crawlerService = crawlerService;
    this.rssFeedParser = rssFeedParser;
    this.filesystemAllowlist = filesystemAllowlist;
    this.libraryRepository = libraryRepository;
    this.libraryAccessService = libraryAccessService;
    this.rssUserAgent = properties.rss().userAgent();
    this.maxPageSizeBytes = properties.rss().maxPageSizeBytes();
    this.maxFeedSizeBytes = properties.rss().maxFeedSizeBytes();
    this.maxFeedEntries = properties.rss().maxEntries();
    this.targetAddressValidator = targetAddressValidator;
    this.confluenceConnectionService = confluenceConnectionService;
  }

  /**
   * Convenience overload for a standalone test carrying no {@code libraryId} (#514's original
   * shape, before #544) - equivalent to {@link #test(SourceConnectionTest, UUID, boolean)} with a
   * {@code null} caller, which that overload only ever consults once {@code request.libraryId()} is
   * set.
   */
  public SourceConnectionTestResult test(SourceConnectionTest request) {
    return test(request, null);
  }

  /**
   * @param caller the caller, only consulted when {@code request.libraryId()} is set (#544) - a
   *     standalone test (no libraryId) keeps #514's original permission bar, checked by the
   *     controller before this method is even called, so {@code caller} may be {@code null} in that
   *     case (see {@link #test(SourceConnectionTest)}).
   */
  public SourceConnectionTestResult test(SourceConnectionTest request, CurrentUser caller) {
    DocumentSourceType sourceType = request.sourceType();
    if (sourceType == null) {
      throw new ValidationException("sourceType ist erforderlich");
    }
    SourceConnectionTest effectiveRequest = request;
    if (request.libraryId() != null) {
      KnowledgeLibrary library = requireManagedLibrary(request.libraryId(), caller);
      if (library.getSourceType() != sourceType) {
        throw new ValidationException(
            "sourceType passt nicht zum gespeicherten Quellentyp dieser Bibliothek");
      }
      effectiveRequest = withStoredCredentialsIfOmitted(request, library);
    }
    return switch (sourceType) {
      case UPLOAD ->
          throw new ValidationException("sourceType UPLOAD unterstützt keinen Verbindungstest");
      case FILESYSTEM -> testFilesystem(effectiveRequest);
      case HTTP_DIRECTORY -> testHttpDirectory(effectiveRequest);
      case RSS_FEED -> testRssFeed(effectiveRequest);
      case CONFLUENCE -> testConfluence(effectiveRequest);
    };
  }

  /**
   * ADR-0023, #1134: detects the edition without credentials and, when credentials are given,
   * verifies them and counts the readable spaces. {@code sourceCredentials} may already be the
   * stored fallback (same origin, {@link #withStoredCredentialsIfOmitted}). An instance problem is
   * the test's result, not an exception.
   */
  private SourceConnectionTestResult testConfluence(SourceConnectionTest request) {
    if (request.sourcePath() != null && !request.sourcePath().isBlank()) {
      throw new ValidationException("sourcePath ist für sourceType CONFLUENCE nicht zulässig");
    }
    if (request.sourceUrl() == null) {
      throw new ValidationException("sourceUrl ist erforderlich, wenn sourceType CONFLUENCE ist");
    }
    ConfluenceConnectionService.Probe probe;
    try {
      probe =
          confluenceConnectionService.probe(
              request.sourceUrl().toString(),
              blankToNull(request.sourceProxy()),
              blankToNull(request.sourceCredentials()),
              Boolean.TRUE.equals(request.sourceInsecureSsl()),
              request.confluenceEdition());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unreachable("Der Verbindungstest wurde unterbrochen.");
    }
    return new SourceConnectionTestResult(
        probe.reachable(),
        probe.message(),
        probe.readableSpaces(),
        probe.detectedEdition(),
        probe.credentialsVerified());
  }

  /**
   * The spaces a Confluence token may read (ADR-0023), for the wizard's selection - the same
   * permission bar, stored-credentials fallback and proxy/TLS forcing as {@link #test}, through the
   * very same {@link #withStoredCredentialsIfOmitted}, so the two paths cannot drift apart.
   */
  public List<ConfluenceSpace> listConfluenceSpaces(
      ConfluenceSpaceListing request, CurrentUser caller) {
    if (request.sourceUrl() == null) {
      throw new ValidationException("sourceUrl ist erforderlich");
    }
    SourceConnectionTest effective =
        new SourceConnectionTest(
            DocumentSourceType.CONFLUENCE,
            null,
            request.sourceUrl(),
            request.sourceProxy(),
            request.sourceCredentials(),
            request.sourceInsecureSsl(),
            request.libraryId(),
            request.confluenceEdition());
    if (request.libraryId() != null) {
      KnowledgeLibrary library = requireManagedLibrary(request.libraryId(), caller);
      if (library.getSourceType() != DocumentSourceType.CONFLUENCE) {
        throw new ValidationException("Die Bibliothek ist keine Confluence-Bibliothek");
      }
      effective = withStoredCredentialsIfOmitted(effective, library);
    }
    String credentials = blankToNull(effective.sourceCredentials());
    if (credentials == null) {
      throw new ValidationException("sourceCredentials sind für die Space-Auflistung erforderlich");
    }
    try {
      return confluenceConnectionService.listSpaces(
          effective.sourceUrl().toString(),
          effective.confluenceEdition(),
          blankToNull(effective.sourceProxy()),
          credentials,
          Boolean.TRUE.equals(effective.sourceInsecureSsl()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ValidationException("Die Space-Auflistung wurde unterbrochen.");
    }
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
   * {@code KnowledgeLibraryService#validateSourceConfigurationForUpdate}) - a no-op for FILESYSTEM,
   * which carries no sourceUrl/sourceCredentials at all.
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
   */
  private SourceConnectionTest withStoredCredentialsIfOmitted(
      SourceConnectionTest request, KnowledgeLibrary library) {
    if (blankToNull(request.sourceCredentials()) != null) {
      return request;
    }
    String requestSourceUrl = request.sourceUrl() == null ? null : request.sourceUrl().toString();
    if (!SourceOriginMatcher.sameOrigin(library.getSourceUrl(), requestSourceUrl)) {
      return request;
    }
    return new SourceConnectionTest(
        request.sourceType(),
        request.sourcePath(),
        request.sourceUrl(),
        library.getSourceProxy(),
        library.getSourceCredentials(),
        library.isSourceInsecureSsl(),
        request.libraryId(),
        request.confluenceEdition());
  }

  private SourceConnectionTestResult testFilesystem(SourceConnectionTest request) {
    String sourcePath = blankToNull(request.sourcePath());
    if (sourcePath == null) {
      throw new ValidationException("sourcePath ist erforderlich, wenn sourceType FILESYSTEM ist");
    }
    // PR #537 review, nit 7: mirrors KnowledgeLibraryService#validateConfigurationForType's
    // FILESYSTEM branch - without this, a client could get a green test for a combination the
    // subsequent createLibrary call rejects outright with 400.
    String sourceUrl =
        blankToNull(request.sourceUrl() == null ? null : request.sourceUrl().toString());
    String sourceProxy = blankToNull(request.sourceProxy());
    String sourceCredentials = blankToNull(request.sourceCredentials());
    if (sourceUrl != null || sourceProxy != null || sourceCredentials != null) {
      throw new ValidationException(
          "sourceUrl, sourceProxy und sourceCredentials sind für sourceType FILESYSTEM nicht"
              + " zulässig");
    }
    if (Boolean.TRUE.equals(request.sourceInsecureSsl())) {
      throw new ValidationException(
          "sourceInsecureSsl ist für sourceType FILESYSTEM nicht zulässig");
    }
    // Path.of(...).isAbsolute() rather than a literal startsWith("/") (unlike
    // KnowledgeLibraryService's identical-looking check): this method actually touches the
    // filesystem below, and a portable absoluteness check is what lets
    // SourceConnectionTestServiceTest exercise the real discoverFiles path against a genuine
    // @TempDir on every OS the test suite runs on, not only one whose native path separator
    // happens to be "/".
    if (!Path.of(sourcePath).isAbsolute()) {
      throw new ValidationException("sourcePath muss ein absoluter Pfad sein");
    }
    // #484/ADR-0018 Entscheidung 6: the same allowlist gate createLibrary itself enforces - the
    // actual security boundary against path enumeration, checked before anything on disk is
    // touched.
    if (!filesystemAllowlist.isConfigured()) {
      throw new ValidationException(
          "sourceType FILESYSTEM ist deaktiviert: der Betrieb hat keine Verzeichnisse für"
              + " Dateisystem-Bibliotheken freigegeben");
    }
    if (!filesystemAllowlist.isAllowed(sourcePath)) {
      throw new ValidationException(
          "sourcePath liegt außerhalb der vom Betrieb freigegebenen Verzeichnisse. Die"
              + " freigegebenen Basisverzeichnisse teilt die Systemverwaltung mit.");
    }

    Path directory = Path.of(sourcePath);
    if (!Files.exists(directory)) {
      return unreachable("Das Verzeichnis existiert nicht.");
    }
    if (!Files.isDirectory(directory)) {
      return unreachable("Der angegebene Pfad ist kein Verzeichnis.");
    }
    if (!Files.isReadable(directory)) {
      return unreachable("Das Verzeichnis ist für den Server nicht lesbar.");
    }

    try {
      DocumentService.DiscoveredFiles discovered = documentService.discoverFiles(directory);
      long count = discovered.supported().size();
      return reachable(
          "Verzeichnis erreichbar, " + count + " " + documentWord(count) + " gefunden.", count);
    } catch (IOException e) {
      log.warn("Filesystem source test failed to read {}: {}", sourcePath, e.getMessage());
      return unreachable("Das Verzeichnis konnte nicht gelesen werden.");
    }
  }

  private SourceConnectionTestResult testHttpDirectory(SourceConnectionTest request) {
    String url = requireHttpUrl(request);
    // PR #537 review, nit 5: mirrors UrlIndexingExecutor#execute exactly - a trailing slash is
    // only appended when the URL does not already look like a file (e.g. ".../index.html"),
    // otherwise the test appends one, turns a working address into ".../index.html/", and reports
    // a false-negative 404 for a URL the later real run would have fetched successfully unchanged.
    if (!url.endsWith("/") && !UrlIndexingExecutor.hasFileExtension(url)) {
      url = url + "/";
    }
    ProxyAndCredentials config = parseProxyAndCredentials(request);
    HttpClient httpClient =
        SourceHttpClientFactory.buildHttpClient(
            config.proxyHost(),
            config.proxyPort(),
            Boolean.TRUE.equals(request.sourceInsecureSsl()));
    String authHeader =
        SourceHttpClientFactory.buildAuthHeader(config.username(), config.password());

    Map<String, String> headers = new LinkedHashMap<>();
    if (authHeader != null) {
      headers.put("Authorization", authHeader);
    }

    try {
      // PR #699 review, "vorbestehend": sourceProxy is exactly as caller-controlled as the target
      // URL itself and determines where the TCP connection actually goes - validated the same way
      // before this test (or the run it mirrors) ever contacts it, not just the target host.
      targetAddressValidator.validateHost(config.proxyHost());
      // #538: Authorization (the tested source configuration's own credentials) must not be
      // replayed to a redirect target on a different host/scheme - see
      // RedirectFollowingFetcher.sendFollowingRedirects's Javadoc.
      HttpResponse<InputStream> response =
          RedirectFollowingFetcher.sendFollowingRedirects(
              httpClient,
              url,
              REQUEST_TIMEOUT,
              headers,
              targetAddressValidator,
              RedirectFollowingFetcher.RedirectPolicy.DROP_AUTHORIZATION_OFF_ORIGIN);
      try (InputStream body = response.body()) {
        if (response.statusCode() == 401) {
          return unreachable(
              "Die Zugangsdaten wurden vom Server abgelehnt (HTTP 401 Unauthorized).");
        }
        if (response.statusCode() == 403) {
          return unreachable("Der Zugriff wurde vom Server verweigert (HTTP 403 Forbidden).");
        }
        if (response.statusCode() == 404) {
          return unreachable("Die Adresse wurde auf dem Server nicht gefunden (HTTP 404).");
        }
        if (response.statusCode() != 200) {
          return unreachable("Der Server antwortete mit HTTP " + response.statusCode() + ".");
        }
        byte[] bytes;
        try {
          bytes = readBounded(body, maxPageSizeBytes);
        } catch (ResponseTooLargeException e) {
          return unreachable(
              "Die Verzeichnisseite überschreitet die zulässige Größe von "
                  + maxPageSizeBytes
                  + " Byte.");
        }
        String html = new String(bytes, StandardCharsets.UTF_8);
        List<AutoindexCrawlerService.CrawledFileEntry> entries =
            crawlerService.parseTopLevelEntries(html, url);
        // PR #537 review, nit 4: filtered by SupportedDocumentFormats, so "Dokumente" here means
        // roughly what it means for an actual run - not just any linked entry (a directory of 30
        // .zip files would otherwise be reported as "30 documents found" while the real run
        // indexes zero and skips all 30). Still only the top level, unlike the run's recursive
        // crawl - a synchronous, rate-limited probe deliberately does not recurse an arbitrary
        // external directory tree (see this class's own Javadoc on why timeouts here are kept
        // short); documented as "oberste Ebene" in both the response wording and the OpenAPI
        // schema description rather than silently under-reporting subdirectory content.
        //
        // #404: an approximation by name only, deliberately - the run itself now decides
        // acceptance from each file's actually downloaded content, but downloading every linked
        // entry just to preview a count here would turn a short connectivity probe into a full
        // crawl. A file whose extension does not match its content shows up here under whatever
        // its name promises, and only the real run's own per-file decision (and its
        // FORMAT_MISMATCH event, see UrlIndexingExecutor#execute) is authoritative.
        long linkedDocuments =
            entries.stream()
                .filter(e -> !e.isDirectory())
                .filter(e -> SupportedDocumentFormats.isSupported(e.name()))
                .count();
        // #550: an empty result can mean two very different things - a directory listing that is
        // genuinely empty (still a valid, working source), or a page that isn't a directory
        // listing this class recognizes at all (a login page, an error page, a plain website).
        // Only the latter gets the more explanatory response; a recognized-but-empty listing keeps
        // reporting the same "0 gefunden" it always did.
        if (linkedDocuments == 0 && !crawlerService.looksLikeDirectoryListing(html)) {
          return unreachable(
              "Die Adresse antwortet, liefert aber kein erkennbares Verzeichnislisting.");
        }
        return reachable(
            "Webverzeichnis erreichbar, "
                + supportedDocumentPhrase(linkedDocuments)
                + " auf oberster Ebene gefunden.",
            linkedDocuments);
      }
    } catch (IOException e) {
      log.warn("HTTP_DIRECTORY source test failed for {}: {}", url, e.getMessage());
      return unreachable(translateConnectionError(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unreachable("Die Verbindung wurde unterbrochen.");
    }
  }

  private SourceConnectionTestResult testRssFeed(SourceConnectionTest request) {
    String url = requireHttpUrl(request);
    ProxyAndCredentials config = parseProxyAndCredentials(request);
    HttpClient httpClient =
        SourceHttpClientFactory.buildHttpClient(
            config.proxyHost(),
            config.proxyPort(),
            Boolean.TRUE.equals(request.sourceInsecureSsl()));
    String authHeader =
        SourceHttpClientFactory.buildAuthHeader(config.username(), config.password());

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("User-Agent", rssUserAgent);
    if (authHeader != null) {
      headers.put("Authorization", authHeader);
    }

    try {
      // PR #699 review, "vorbestehend" - see testHttpDirectory's identical call above.
      targetAddressValidator.validateHost(config.proxyHost());
      // #538: same reasoning as testHttpDirectory above.
      HttpResponse<InputStream> response =
          RedirectFollowingFetcher.sendFollowingRedirects(
              httpClient,
              url,
              REQUEST_TIMEOUT,
              headers,
              targetAddressValidator,
              RedirectFollowingFetcher.RedirectPolicy.DROP_AUTHORIZATION_OFF_ORIGIN);
      try (InputStream body = response.body()) {
        if (response.statusCode() == 401) {
          return unreachable(
              "Die Zugangsdaten wurden vom Server abgelehnt (HTTP 401 Unauthorized).");
        }
        if (response.statusCode() != 200) {
          return unreachable("Der Server antwortete mit HTTP " + response.statusCode() + ".");
        }
        byte[] bytes;
        try {
          bytes = readBounded(body, maxFeedSizeBytes);
        } catch (ResponseTooLargeException e) {
          return unreachable(
              "Der RSS-Feed überschreitet die zulässige Größe von " + maxFeedSizeBytes + " Byte.");
        }
        List<RssFeedEntry> entries;
        try {
          entries = rssFeedParser.parse(new ByteArrayInputStream(bytes));
        } catch (RssFeedParseException e) {
          // Already German and user-facing (see that class's Javadoc) - passed through as-is.
          return unreachable(e.getMessage());
        }
        // PR #537 review ("zwei weitere Kleinigkeiten"): capped at opaa.indexing.rss.max-entries,
        // mirroring RssFeedIndexingExecutor#execute's own truncation - a feed carrying more entries
        // than a run ever processes must not be reported with a count the run itself never reaches.
        int totalEntries = entries.size();
        int countedEntries = Math.min(totalEntries, maxFeedEntries);
        String message =
            "RSS-Feed erreichbar, "
                + countedEntries
                + " "
                + (countedEntries == 1 ? "Eintrag" : "Einträge")
                + " gefunden.";
        if (totalEntries > countedEntries) {
          message +=
              " Der Feed enthält insgesamt "
                  + totalEntries
                  + " Einträge; ein Lauf verarbeitet"
                  + " davon höchstens "
                  + maxFeedEntries
                  + ".";
        }
        return reachable(message, countedEntries);
      }
    } catch (IOException e) {
      log.warn("RSS_FEED source test failed for {}: {}", url, e.getMessage());
      return unreachable(translateConnectionError(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unreachable("Die Verbindung wurde unterbrochen.");
    }
  }

  private String requireHttpUrl(SourceConnectionTest request) {
    String sourceUrl =
        blankToNull(request.sourceUrl() == null ? null : request.sourceUrl().toString());
    if (sourceUrl == null) {
      throw new ValidationException("sourceUrl ist erforderlich");
    }
    // PR #537 review, nit 7: mirrors KnowledgeLibraryService#validateUrlBasedConfiguration - a
    // sourcePath alongside a URL-based sourceType is rejected at creation time, so accepting it
    // silently here would again let a client see a green test for a combination createLibrary
    // itself refuses.
    if (blankToNull(request.sourcePath()) != null) {
      throw new ValidationException(
          "sourcePath ist für sourceType " + request.sourceType() + " nicht zulässig");
    }
    URI uri;
    try {
      uri = URI.create(sourceUrl);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("sourceUrl ist keine gültige URL");
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new ValidationException("sourceUrl muss mit http:// oder https:// beginnen");
    }
    return sourceUrl;
  }

  private static String documentWord(long count) {
    return count == 1 ? "Dokument" : "Dokumente";
  }

  /**
   * Formats {@code count} together with a grammatically correct singular/plural German phrase
   * (#551: "1 unterstütztes Dokument" vs. "N unterstützte Dokumente" - the adjective ending must
   * agree with the noun, not just the noun itself).
   */
  private static String supportedDocumentPhrase(long count) {
    return count
        + " "
        + (count == 1 ? "unterstütztes" : "unterstützte")
        + " "
        + documentWord(count);
  }

  private static SourceConnectionTestResult reachable(String message, long documentCount) {
    return new SourceConnectionTestResult(true, message, documentCount);
  }

  private static SourceConnectionTestResult unreachable(String message) {
    return new SourceConnectionTestResult(false, message, null);
  }

  /**
   * Translates a connection-level {@link IOException} into German, user-facing text (#514
   * acceptance criteria: "Fehlermeldungen sind deutsch und verstaendlich") - never the exception's
   * own (English, sometimes internals-revealing) message.
   */
  private static String translateConnectionError(IOException e) {
    // #267: TargetAddressValidator's own message is already German, user-facing and never carries
    // more than the rejected host/scheme itself - shown as-is, ahead of the generic fallback below.
    if (e instanceof TargetAddressValidator.TargetAddressBlockedException) {
      return e.getMessage();
    }
    if (e instanceof UnknownHostException) {
      return "Der Host konnte nicht gefunden werden (DNS-Auflösung fehlgeschlagen).";
    }
    if (e instanceof ConnectException) {
      return "Die Verbindung wurde vom Server abgelehnt.";
    }
    if (e instanceof HttpTimeoutException || e instanceof SocketTimeoutException) {
      return "Die Verbindung ist in ein Zeitlimit gelaufen.";
    }
    if (e instanceof SSLException) {
      return "Das Zertifikat des Servers konnte nicht geprüft werden. Bei einem bekannten,"
          + " selbstsignierten Zertifikat kann die Zertifikatsprüfung ausgesetzt werden.";
    }
    return "Die Adresse ist nicht erreichbar.";
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /**
   * Reads at most {@code maxBytes} from {@code in}, throwing {@link ResponseTooLargeException} the
   * moment a further byte would exceed the limit - enforced while streaming, mirroring {@code
   * FeedFetcher#readFeedBody}/{@code BoundedDownloader#readBounded} (PR #537 review, finding 2).
   */
  private static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
    byte[] probe = in.readNBytes(Math.toIntExact(Math.min(maxBytes + 1, Integer.MAX_VALUE)));
    if (probe.length > maxBytes) {
      throw new ResponseTooLargeException();
    }
    return probe;
  }

  /** Thrown by {@link #readBounded} when the configured byte limit is exceeded while streaming. */
  private static final class ResponseTooLargeException extends RuntimeException {}

  /**
   * Parses {@code sourceProxy} (host:port) and {@code sourceCredentials} (user:password) via the
   * shared {@link ProxyAndCredentials#parse} (PR #642 review, finding 4: this used to be a private
   * copy of the identical parsing {@code UrlIndexingExecutor#execute} and {@code
   * RssFeedIndexingExecutor#execute} each also carried) - an invalid {@code sourceProxy} port
   * becomes this endpoint's usual {@code 400}, unlike the two indexing executors, which fail the
   * asynchronous job instead of answering a synchronous HTTP request.
   */
  private static ProxyAndCredentials parseProxyAndCredentials(SourceConnectionTest request) {
    try {
      return ProxyAndCredentials.parse(
          blankToNull(request.sourceProxy()), blankToNull(request.sourceCredentials()));
    } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
      throw new ValidationException(e.getMessage());
    }
  }
}
