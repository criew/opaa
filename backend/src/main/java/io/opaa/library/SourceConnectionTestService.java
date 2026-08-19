package io.opaa.library;

import io.opaa.api.dto.SourceConnectionTestRequest;
import io.opaa.api.dto.SourceConnectionTestResponse;
import io.opaa.indexing.AutoindexCrawlerService;
import io.opaa.indexing.DocumentService;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.indexing.FilesystemPathAllowlist;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.RssFeedEntry;
import io.opaa.indexing.RssFeedParseException;
import io.opaa.indexing.RssFeedParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
 * the exact methods {@code UrlIndexingExecutor} and {@code RssFeedIndexingExecutor} use, so this
 * test checks what a run will actually do - not a simplified stand-in that could pass while the
 * real run fails (or the reverse). Unlike {@code RssFeedIndexingExecutor} (#505: it does not yet
 * apply proxy/credentials to its feed fetch at all), this test applies them for RSS_FEED too - the
 * target behaviour, not the RSS executor's current gap.
 *
 * <p><b>Security (#514 acceptance criteria).</b> This endpoint lets any caller with the right to
 * create a library probe arbitrary server-local paths (FILESYSTEM) and arbitrary URLs
 * (HTTP_DIRECTORY/RSS_FEED) - the same path-enumeration/SSRF surface {@code
 * validateConfigurationForType} already reasons about for creation itself. FILESYSTEM is therefore
 * gated by the identical {@link FilesystemPathAllowlist} check creation applies, before anything on
 * disk is touched, and no response ever reveals more about a directory's contents than a count -
 * never a file name, a listing, or an exception's raw text.
 */
@Service
public class SourceConnectionTestService {

  private static final Logger log = LoggerFactory.getLogger(SourceConnectionTestService.class);

  private final DocumentService documentService;
  private final AutoindexCrawlerService crawlerService;
  private final RssFeedParser rssFeedParser;
  private final FilesystemPathAllowlist filesystemAllowlist;
  private final String rssUserAgent;

  public SourceConnectionTestService(
      DocumentService documentService,
      AutoindexCrawlerService crawlerService,
      RssFeedParser rssFeedParser,
      FilesystemPathAllowlist filesystemAllowlist,
      IndexingProperties properties) {
    this.documentService = documentService;
    this.crawlerService = crawlerService;
    this.rssFeedParser = rssFeedParser;
    this.filesystemAllowlist = filesystemAllowlist;
    this.rssUserAgent = properties.rss().userAgent();
  }

  public SourceConnectionTestResponse test(SourceConnectionTestRequest request) {
    DocumentSourceType sourceType = request.getSourceType();
    if (sourceType == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceType ist erforderlich");
    }
    return switch (sourceType) {
      case UPLOAD ->
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "sourceType UPLOAD unterstuetzt keinen Verbindungstest");
      case FILESYSTEM -> testFilesystem(request);
      case HTTP_DIRECTORY -> testHttpDirectory(request);
      case RSS_FEED -> testRssFeed(request);
    };
  }

  private SourceConnectionTestResponse testFilesystem(SourceConnectionTestRequest request) {
    String sourcePath = blankToNull(request.getSourcePath());
    if (sourcePath == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "sourcePath ist erforderlich, wenn sourceType FILESYSTEM ist");
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
          "sourceType FILESYSTEM ist deaktiviert: der Betrieb hat keine Verzeichnisse fuer"
              + " Dateisystem-Bibliotheken freigegeben");
    }
    if (!filesystemAllowlist.isAllowed(sourcePath)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "sourcePath liegt ausserhalb der vom Betrieb freigegebenen Verzeichnisse. Die"
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
      return unreachable("Das Verzeichnis ist fuer den Server nicht lesbar.");
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
    if (!url.endsWith("/")) {
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

    HttpRequest.Builder reqBuilder =
        HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30)).GET();
    if (authHeader != null) {
      reqBuilder.header("Authorization", authHeader);
    }

    try {
      HttpResponse<String> response =
          httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 401) {
        return unreachable("Die Zugangsdaten wurden vom Server abgelehnt (HTTP 401 Unauthorized).");
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
      List<AutoindexCrawlerService.CrawledFileEntry> entries =
          crawlerService.parseTopLevelEntries(response.body(), url);
      long linkedDocuments = entries.stream().filter(e -> !e.isDirectory()).count();
      return reachable(
          "Webverzeichnis erreichbar, "
              + linkedDocuments
              + " verlinkte "
              + documentWord(linkedDocuments)
              + " gefunden.",
          linkedDocuments);
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

    HttpRequest.Builder reqBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", rssUserAgent)
            .GET();
    if (authHeader != null) {
      reqBuilder.header("Authorization", authHeader);
    }

    try {
      HttpResponse<byte[]> response =
          httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() == 401) {
        return unreachable("Die Zugangsdaten wurden vom Server abgelehnt (HTTP 401 Unauthorized).");
      }
      if (response.statusCode() != 200) {
        return unreachable("Der Server antwortete mit HTTP " + response.statusCode() + ".");
      }
      List<RssFeedEntry> entries;
      try {
        entries = rssFeedParser.parse(new ByteArrayInputStream(response.body()));
      } catch (RssFeedParseException e) {
        // Already German and user-facing (see that class's Javadoc) - passed through as-is.
        return unreachable(e.getMessage());
      }
      return reachable(
          "RSS-Feed erreichbar, "
              + entries.size()
              + " "
              + (entries.size() == 1 ? "Eintrag" : "Eintraege")
              + " gefunden.",
          (long) entries.size());
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
    URI uri;
    try {
      uri = URI.create(sourceUrl);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceUrl ist keine gueltige URL");
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
      return "Der Host konnte nicht gefunden werden (DNS-Aufloesung fehlgeschlagen).";
    }
    if (e instanceof ConnectException) {
      return "Die Verbindung wurde vom Server abgelehnt.";
    }
    if (e instanceof HttpTimeoutException || e instanceof SocketTimeoutException) {
      return "Die Verbindung ist in ein Zeitlimit gelaufen.";
    }
    if (e instanceof SSLException) {
      return "Das Zertifikat des Servers konnte nicht geprueft werden. Bei einem bekannten,"
          + " selbstsignierten Zertifikat kann die Zertifikatspruefung ausgesetzt werden.";
    }
    return "Die Adresse ist nicht erreichbar.";
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

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
