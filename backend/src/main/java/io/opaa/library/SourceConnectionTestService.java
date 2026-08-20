package io.opaa.library;

import io.opaa.api.dto.SourceConnectionTestRequest;
import io.opaa.api.dto.SourceConnectionTestResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.indexing.AutoindexCrawlerService;
import io.opaa.indexing.DocumentService;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.indexing.FilesystemPathAllowlist;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.RssFeedEntry;
import io.opaa.indexing.RssFeedParseException;
import io.opaa.indexing.RssFeedParser;
import io.opaa.indexing.SupportedDocumentFormats;
import io.opaa.indexing.UrlIndexingExecutor;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tests a source configuration <em>before</em> a library is created (#514) - the same three checks
 * {@link KnowledgeLibraryService#createLibrary} and the corresponding {@code
 * io.opaa.indexing.SourceIndexingExecutor} would otherwise only surface much later, at the first
 * indexing run: whether a FILESYSTEM directory exists and is readable, whether an HTTP_DIRECTORY
 * page answers under the configured proxy/credentials/certificate settings, and whether an RSS_FEED
 * URL serves a parseable feed.
 *
 * <p><b>Same building blocks as the real runs, deliberately</b> (issue #514): {@link
 * AutoindexCrawlerService#buildHttpClient} and {@link AutoindexCrawlerService#buildAuthHeader} are
 * the exact methods {@code UrlIndexingExecutor} and {@code RssFeedIndexingExecutor} use, and the
 * response body is bounded exactly the way {@code RssFeedIndexingExecutor#readBounded} and {@code
 * UrlFileDownloader#readBounded} bound theirs - {@link IndexingProperties.Rss#maxPageSizeBytes()}
 * for the HTTP_DIRECTORY listing page, {@link IndexingProperties.Rss#maxFeedSizeBytes()} for the
 * RSS feed (PR #537 review, finding 2: an unbounded read here let an authenticated caller crash the
 * whole backend with a single request against an endless or multi-gigabyte response). Unlike {@code
 * RssFeedIndexingExecutor} (#505: it does not yet apply proxy/credentials to its feed fetch at
 * all), this test applies them for RSS_FEED too - the target behaviour, not the RSS executor's
 * current gap.
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
 * validation for HTTP_DIRECTORY/RSS_FEED addresses themselves (blocking internal/private ranges)
 * remains #267's responsibility, same as for the indexing run this tests - see
 * docs/features/knowledge-sources.md.
 *
 * <p><b>Testing an existing library's stored quellkonfiguration (#544).</b> {@link
 * SourceConnectionTestRequest#getLibraryId()} lets {@code EditLibrarySourceDialog} test a
 * password-protected source without forcing the caller to re-type a credential the library already
 * has stored - reachable only with at least {@link AssetRole#MANAGER} on that library (see {@link
 * #requireManagedLibrary}), via {@link LibraryAccessService#requireRole} (#436), the same
 * not-found/forbidden split every other library-scoped endpoint now uses. The library's own {@code
 * sourceType} must match this request's (otherwise 400 - #544 acceptance criterion), and a missing
 * {@code sourceCredentials} falls back to the library's stored one only when {@code sourceUrl}
 * still names the same origin as the library's own stored {@code sourceUrl} - the identical {@link
 * SourceOriginMatcher} rule {@link KnowledgeLibraryService#validateSourceConfigurationForUpdate}
 * already applies when saving, so a caller pointed at a different host cannot silently reuse a
 * credential it never entered.
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
  private final UserRepository userRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService libraryAccessService;
  private final String rssUserAgent;
  private final long maxPageSizeBytes;
  private final long maxFeedSizeBytes;
  private final int maxFeedEntries;

  public SourceConnectionTestService(
      DocumentService documentService,
      AutoindexCrawlerService crawlerService,
      RssFeedParser rssFeedParser,
      FilesystemPathAllowlist filesystemAllowlist,
      UserRepository userRepository,
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService libraryAccessService,
      IndexingProperties properties) {
    this.documentService = documentService;
    this.crawlerService = crawlerService;
    this.rssFeedParser = rssFeedParser;
    this.filesystemAllowlist = filesystemAllowlist;
    this.userRepository = userRepository;
    this.libraryRepository = libraryRepository;
    this.libraryAccessService = libraryAccessService;
    this.rssUserAgent = properties.rss().userAgent();
    this.maxPageSizeBytes = properties.rss().maxPageSizeBytes();
    this.maxFeedSizeBytes = properties.rss().maxFeedSizeBytes();
    this.maxFeedEntries = properties.rss().maxEntries();
  }

  /**
   * Convenience overload for a standalone test carrying no {@code libraryId} (#514's original
   * shape, before #544) - equivalent to {@link #test(SourceConnectionTestRequest, UUID)} with a
   * {@code null} caller, which that overload only ever consults once {@code request.getLibraryId()}
   * is set.
   */
  public SourceConnectionTestResponse test(SourceConnectionTestRequest request) {
    return test(request, null);
  }

  /**
   * @param currentUserId the caller, only consulted when {@code request.getLibraryId()} is set
   *     (#544) - a standalone test (no libraryId) keeps #514's original permission bar, checked by
   *     the controller before this method is even called.
   */
  public SourceConnectionTestResponse test(
      SourceConnectionTestRequest request, UUID currentUserId) {
    DocumentSourceType sourceType = request.getSourceType();
    if (sourceType == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceType ist erforderlich");
    }
    SourceConnectionTestRequest effectiveRequest = request;
    if (request.getLibraryId() != null) {
      KnowledgeLibrary library = requireManagedLibrary(request.getLibraryId(), currentUserId);
      if (library.getSourceType() != sourceType) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "sourceType passt nicht zum gespeicherten Quellentyp dieser Bibliothek");
      }
      effectiveRequest = withStoredCredentialsIfOmitted(request, library);
    }
    return switch (sourceType) {
      case UPLOAD ->
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "sourceType UPLOAD unterstützt keinen Verbindungstest");
      case FILESYSTEM -> testFilesystem(effectiveRequest);
      case HTTP_DIRECTORY -> testHttpDirectory(effectiveRequest);
      case RSS_FEED -> testRssFeed(effectiveRequest);
    };
  }

  /**
   * Resolves {@code libraryId} and enforces both the organization boundary and the {@link
   * AssetRole#MANAGER} bar (#544) via {@link LibraryAccessService#requireRole} (#436) - 404 if the
   * library does not exist, belongs to another organization, or the caller holds no role on it at
   * all (indistinguishable from "does not exist" - the org boundary/lack of any grant must not leak
   * even that much), 403 if the caller's role is below MANAGER, the same distinction every other
   * library-scoped endpoint now makes (e.g. {@code KnowledgeLibraryService#updateLibrary}, {@code
   * DocumentIndexingService#requireEditableLibrary}).
   */
  private KnowledgeLibrary requireManagedLibrary(UUID libraryId, UUID currentUserId) {
    User currentUser =
        userRepository
            .findById(currentUserId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Benutzer nicht gefunden"));
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .filter(l -> l.getOrganizationId().equals(currentUser.getOrganizationId()))
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Bibliothek nicht gefunden"));
    // No system-admin bypass here (systemAdmin always false), mirroring
    // DocumentIndexingService#requireEditableLibrary - the real grant/visibility formula decides,
    // unconditionally, same as every other library-scoped write action.
    libraryAccessService.requireRole(library, currentUserId, false, AssetRole.MANAGER);
    return library;
  }

  /**
   * Fills in the library's own stored {@code sourceCredentials} when the request carries none and
   * {@code sourceUrl} still names the same origin as the library's stored one (#544, same rule as
   * {@code KnowledgeLibraryService#validateSourceConfigurationForUpdate}) - a no-op for FILESYSTEM,
   * which carries no sourceUrl/sourceCredentials at all.
   */
  private SourceConnectionTestRequest withStoredCredentialsIfOmitted(
      SourceConnectionTestRequest request, KnowledgeLibrary library) {
    if (blankToNull(request.getSourceCredentials()) != null) {
      return request;
    }
    String requestSourceUrl =
        request.getSourceUrl() == null ? null : request.getSourceUrl().toString();
    if (!SourceOriginMatcher.sameOrigin(library.getSourceUrl(), requestSourceUrl)) {
      return request;
    }
    return new SourceConnectionTestRequest(request.getSourceType())
        .sourcePath(request.getSourcePath())
        .sourceUrl(request.getSourceUrl())
        .sourceProxy(request.getSourceProxy())
        .sourceCredentials(library.getSourceCredentials())
        .sourceInsecureSsl(request.getSourceInsecureSsl())
        .libraryId(request.getLibraryId());
  }

  private SourceConnectionTestResponse testFilesystem(SourceConnectionTestRequest request) {
    String sourcePath = blankToNull(request.getSourcePath());
    if (sourcePath == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "sourcePath ist erforderlich, wenn sourceType FILESYSTEM ist");
    }
    // PR #537 review, nit 7: mirrors KnowledgeLibraryService#validateConfigurationForType's
    // FILESYSTEM branch - without this, a client could get a green test for a combination the
    // subsequent createLibrary call rejects outright with 400.
    String sourceUrl =
        blankToNull(request.getSourceUrl() == null ? null : request.getSourceUrl().toString());
    String sourceProxy = blankToNull(request.getSourceProxy());
    String sourceCredentials = blankToNull(request.getSourceCredentials());
    if (sourceUrl != null || sourceProxy != null || sourceCredentials != null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "sourceUrl, sourceProxy und sourceCredentials sind für sourceType FILESYSTEM nicht"
              + " zulässig");
    }
    if (Boolean.TRUE.equals(request.getSourceInsecureSsl())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "sourceInsecureSsl ist für sourceType FILESYSTEM nicht zulässig");
    }
    // Path.of(...).isAbsolute() rather than a literal startsWith("/") (unlike
    // KnowledgeLibraryService's identical-looking check): this method actually touches the
    // filesystem below, and a portable absoluteness check is what lets
    // SourceConnectionTestServiceTest exercise the real discoverFiles path against a genuine
    // @TempDir on every OS the test suite runs on, not only one whose native path separator
    // happens to be "/".
    if (!Path.of(sourcePath).isAbsolute()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "sourcePath muss ein absoluter Pfad sein");
    }
    // #484/ADR-0018 Entscheidung 6: the same allowlist gate createLibrary itself enforces - the
    // actual security boundary against path enumeration, checked before anything on disk is
    // touched.
    if (!filesystemAllowlist.isConfigured()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "sourceType FILESYSTEM ist deaktiviert: der Betrieb hat keine Verzeichnisse für"
              + " Dateisystem-Bibliotheken freigegeben");
    }
    if (!filesystemAllowlist.isAllowed(sourcePath)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
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

  private SourceConnectionTestResponse testHttpDirectory(SourceConnectionTestRequest request) {
    String url = requireHttpUrl(request);
    // PR #537 review, nit 5: mirrors UrlIndexingExecutor#execute exactly - a trailing slash is
    // only appended when the URL does not already look like a file (e.g. ".../index.html"),
    // otherwise the test appends one, turns a working address into ".../index.html/", and reports
    // a false-negative 404 for a URL the later real run would have fetched successfully unchanged.
    if (!url.endsWith("/") && !UrlIndexingExecutor.hasFileExtension(url)) {
      url = url + "/";
    }
    ProxyAndCredentials config = ProxyAndCredentials.from(request);
    HttpClient httpClient =
        AutoindexCrawlerService.buildHttpClient(
            config.proxyHost(),
            config.proxyPort(),
            Boolean.TRUE.equals(request.getSourceInsecureSsl()));
    String authHeader =
        AutoindexCrawlerService.buildAuthHeader(config.username(), config.password());

    Map<String, String> headers = new LinkedHashMap<>();
    if (authHeader != null) {
      headers.put("Authorization", authHeader);
    }

    try {
      // #538: Authorization (the tested source configuration's own credentials) must not be
      // replayed to a redirect target on a different host/scheme - see
      // AutoindexCrawlerService.sendFollowingRedirects's Javadoc.
      HttpResponse<InputStream> response =
          AutoindexCrawlerService.sendFollowingRedirects(httpClient, url, REQUEST_TIMEOUT, headers);
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
        // PR #537 review, nit 4: filtered by SupportedDocumentFormats, exactly like
        // UrlIndexingExecutor#isSupportedFormat, so "Dokumente" here means what it means for
        // FILESYSTEM - a document the run would actually index, not just any linked entry (a
        // directory of 30 .zip files would otherwise be reported as "30 documents found" while the
        // real run indexes zero and skips all 30). Still only the top level, unlike the run's
        // recursive crawl - a synchronous, rate-limited probe deliberately does not recurse an
        // arbitrary external directory tree (see this class's own Javadoc on why timeouts here are
        // kept short); documented as "oberste Ebene" in both the response wording and the OpenAPI
        // schema description rather than silently under-reporting subdirectory content.
        long linkedDocuments =
            entries.stream()
                .filter(e -> !e.isDirectory())
                .filter(e -> SupportedDocumentFormats.isSupported(e.name()))
                .count();
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

  private SourceConnectionTestResponse testRssFeed(SourceConnectionTestRequest request) {
    String url = requireHttpUrl(request);
    ProxyAndCredentials config = ProxyAndCredentials.from(request);
    HttpClient httpClient =
        AutoindexCrawlerService.buildHttpClient(
            config.proxyHost(),
            config.proxyPort(),
            Boolean.TRUE.equals(request.getSourceInsecureSsl()));
    String authHeader =
        AutoindexCrawlerService.buildAuthHeader(config.username(), config.password());

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("User-Agent", rssUserAgent);
    if (authHeader != null) {
      headers.put("Authorization", authHeader);
    }

    try {
      // #538: same reasoning as testHttpDirectory above.
      HttpResponse<InputStream> response =
          AutoindexCrawlerService.sendFollowingRedirects(httpClient, url, REQUEST_TIMEOUT, headers);
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

  private String requireHttpUrl(SourceConnectionTestRequest request) {
    String sourceUrl =
        blankToNull(request.getSourceUrl() == null ? null : request.getSourceUrl().toString());
    if (sourceUrl == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceUrl ist erforderlich");
    }
    // PR #537 review, nit 7: mirrors KnowledgeLibraryService#validateUrlBasedConfiguration - a
    // sourcePath alongside a URL-based sourceType is rejected at creation time, so accepting it
    // silently here would again let a client see a green test for a combination createLibrary
    // itself refuses.
    if (blankToNull(request.getSourcePath()) != null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "sourcePath ist für sourceType " + request.getSourceType() + " nicht zulässig");
    }
    URI uri;
    try {
      uri = URI.create(sourceUrl);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceUrl ist keine gültige URL");
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "sourceUrl muss mit http:// oder https:// beginnen");
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

  private static SourceConnectionTestResponse reachable(String message, long documentCount) {
    return new SourceConnectionTestResponse(true, message).documentCount(documentCount);
  }

  private static SourceConnectionTestResponse unreachable(String message) {
    return new SourceConnectionTestResponse(false, message);
  }

  /**
   * Translates a connection-level {@link IOException} into German, user-facing text (#514
   * acceptance criteria: "Fehlermeldungen sind deutsch und verstaendlich") - never the exception's
   * own (English, sometimes internals-revealing) message.
   */
  private static String translateConnectionError(IOException e) {
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
   * RssFeedIndexingExecutor#readBounded}/{@code UrlFileDownloader#readBounded} (PR #537 review,
   * finding 2).
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
   * Parses {@code sourceProxy} (host:port) and {@code sourceCredentials} (user:password) the same
   * way {@code UrlIndexingExecutor#execute} does for a real run.
   */
  private record ProxyAndCredentials(
      String proxyHost, int proxyPort, String username, String password) {

    static ProxyAndCredentials from(SourceConnectionTestRequest request) {
      String proxyHost = null;
      int proxyPort = -1;
      String proxy = blankToNull(request.getSourceProxy());
      if (proxy != null) {
        int colonIdx = proxy.lastIndexOf(':');
        if (colonIdx > 0) {
          proxyHost = proxy.substring(0, colonIdx);
          try {
            proxyPort = Integer.parseInt(proxy.substring(colonIdx + 1));
          } catch (NumberFormatException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "sourceProxy muss dem Format host:port entsprechen");
          }
        }
      }

      String username = null;
      String password = null;
      String credentials = blankToNull(request.getSourceCredentials());
      if (credentials != null) {
        int colonIdx = credentials.indexOf(':');
        if (colonIdx > 0) {
          username = credentials.substring(0, colonIdx);
          password = credentials.substring(colonIdx + 1);
        }
      }

      return new ProxyAndCredentials(proxyHost, proxyPort, username, password);
    }
  }
}
