package io.opaa.indexing.source.nextcloud;

import io.opaa.indexing.source.RequestBudget;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.BoundedStreams;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import io.opaa.sourceaccess.SourceRequestMeter;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.net.ssl.SSLException;

/**
 * The WebDAV access of one run or one probe: {@code PROPFIND} and downloads against the files of
 * the technical user, under the shared target validation, {@code User-Agent} and {@code 429}
 * handling of {@code io.opaa.sourceaccess}, every request charged to the {@link RequestBudget}. A
 * redirect is never followed off the instance's origin, and no answer names a target. Every request
 * carries the {@code Authorization} its supplier answers then.
 */
final class NextcloudDav implements AutoCloseable {

  /** Appended when the target validation refuses the host, naming the setting that opens it. */
  static final String ALLOWLIST_HINT = TargetAddressValidator.ALLOWLIST_HINT;

  private final NextcloudConnection connection;
  private final Supplier<String> authorization;
  private final HttpClient httpClient;
  private final TargetAddressValidator targetAddressValidator;
  private final SourceRequestPolicy requestPolicy;
  private final RequestBudget budget;
  private final Duration timeout;
  private final long maxResponseBytes;
  private final BoundedDownloader downloader;
  private String filesRoot;

  NextcloudDav(
      NextcloudConnection connection,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy requestPolicy,
      RequestBudget budget,
      Duration timeout,
      long maxResponseBytes) {
    this(
        connection,
        connection::authorizationHeader,
        targetAddressValidator,
        requestPolicy,
        budget,
        timeout,
        maxResponseBytes);
  }

  NextcloudDav(
      NextcloudConnection connection,
      Supplier<String> authorization,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy requestPolicy,
      RequestBudget budget,
      Duration timeout,
      long maxResponseBytes) {
    this.connection = connection;
    this.authorization = authorization;
    this.httpClient =
        SourceHttpClientFactory.buildHttpClient(
            connection.proxyHost(), connection.proxyPort(), connection.insecureSsl());
    this.targetAddressValidator = targetAddressValidator;
    this.requestPolicy = requestPolicy;
    this.budget = budget;
    this.timeout = timeout;
    this.maxResponseBytes = maxResponseBytes;
    this.downloader = new BoundedDownloader(targetAddressValidator, requestPolicy);
  }

  NextcloudConnection connection() {
    return connection;
  }

  SourceRequestMeter meter() {
    return budget.meter();
  }

  /**
   * The encoded absolute path of the technical user's files, {@code …/remote.php/dav/files/<uid>},
   * from the principal the instance names for the signed-in user - the login name and the user id
   * differ under LDAP. Asked once.
   */
  String filesRoot() throws NextcloudAccessException, InterruptedException {
    if (filesRoot != null) {
      return filesRoot;
    }
    String resource = "die Anmeldung";
    String principal;
    try (InputStream body =
        propfindBody(connection.davRoot(), 0, DavMultistatus.PRINCIPAL_BODY, resource)) {
      principal = DavMultistatus.principalHref(body);
    } catch (DavMultistatus.DavFormatException e) {
      throw notNextcloud(resource);
    } catch (IOException e) {
      throw failure(e, resource);
    }
    String marker = "/principals/users/";
    int at = principal == null ? -1 : principal.indexOf(marker);
    if (at < 0) {
      throw notNextcloud(resource);
    }
    String userId = principal.substring(at + marker.length());
    while (userId.endsWith("/")) {
      userId = userId.substring(0, userId.length() - 1);
    }
    if (userId.isEmpty() || userId.contains("/")) {
      throw notNextcloud(resource);
    }
    filesRoot = connection.davRoot() + "files/" + userId;
    return filesRoot;
  }

  /**
   * {@code PROPFIND} with {@code depth} 0 or 1 on {@code encodedPath}, an absolute path on the
   * instance; {@code resource} is the German noun phrase failure messages name.
   */
  List<DavResource> propfind(String encodedPath, int depth, String resource)
      throws NextcloudAccessException, InterruptedException {
    try (InputStream body =
        propfindBody(encodedPath, depth, DavMultistatus.PROPFIND_BODY, resource)) {
      return DavMultistatus.parse(body);
    } catch (DavMultistatus.DavFormatException e) {
      throw notNextcloud(resource);
    } catch (BoundedStreams.LimitExceededException e) {
      throw new NextcloudAccessException(
          "Die Nextcloud-Antwort für "
              + resource
              + " überschreitet "
              + maxResponseBytes
              + " Bytes (opaa.indexing.nextcloud.max-response-bytes).");
    } catch (IOException e) {
      throw failure(e, resource);
    }
  }

  /** Downloads {@code encodedPath} into a temp file <b>the caller deletes</b>. */
  BoundedDownloader.DownloadedFile download(String encodedPath, String fileName, long maxBytes)
      throws NextcloudAccessException, InterruptedException {
    String resource = "die Datei „" + fileName + "“";
    try {
      BoundedDownloader.DownloadedFile file =
          downloader.downloadBounded(
              httpClient,
              connection.url(encodedPath),
              fileName,
              maxBytes,
              authorization.get(),
              RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN,
              budget);
      budget.meter().recordBytes(file.path().toFile().length());
      return file;
    } catch (BoundedDownloader.AttachmentTooLargeException e) {
      throw new NextcloudAccessException.TooLarge(
          "Die Datei „" + fileName + "“ ist größer als " + maxBytes + " Bytes.");
    } catch (BoundedDownloader.HttpStatusException e) {
      if (e.statusCode() == 503) {
        throw new NextcloudAccessException.Unopenable(
            "Nextcloud kann " + resource + " derzeit nicht öffnen (HTTP 503).");
      }
      throw status(e.statusCode(), resource);
    } catch (IOException e) {
      throw failure(e, resource);
    }
  }

  private InputStream propfindBody(String encodedPath, int depth, String request, String resource)
      throws IOException, InterruptedException, NextcloudAccessException {
    Map<String, String> headers = requestPolicy.headers(authorization.get());
    headers.put("Depth", Integer.toString(depth));
    HttpResponse<InputStream> response =
        RedirectFollowingFetcher.sendWithBody(
            httpClient,
            "PROPFIND",
            connection.url(encodedPath),
            HttpRequest.BodyPublishers.ofString(request, StandardCharsets.UTF_8),
            "application/xml; charset=utf-8",
            timeout,
            headers,
            targetAddressValidator,
            requestPolicy.rateLimitHandling(budget));
    if (response.statusCode() != 207) {
      response.body().close();
      throw status(response.statusCode(), resource);
    }
    return BoundedStreams.input(response.body(), maxResponseBytes);
  }

  private static NextcloudAccessException status(int status, String resource) {
    return switch (status) {
      case 401 ->
          new NextcloudAccessException.Authentication(
              "Nextcloud hat Benutzername oder App-Passwort abgelehnt (HTTP 401).");
      case 403 ->
          new NextcloudAccessException.Forbidden(
              "Keine Leseberechtigung für " + resource + " (HTTP 403).");
      case 404 ->
          new NextcloudAccessException.NotFound(
              "Nextcloud kennt " + resource + " nicht (HTTP 404).");
      case RedirectFollowingFetcher.TOO_MANY_REQUESTS ->
          new NextcloudAccessException(
              "Nextcloud begrenzt die Anfragerate (HTTP 429); für " + resource + " aufgegeben.");
      default ->
          new NextcloudAccessException(
              "Nextcloud antwortete für " + resource + " mit HTTP " + status + ".");
    };
  }

  private NextcloudAccessException notNextcloud(String resource) {
    return new NextcloudAccessException.Unreachable(
        "Unter "
            + connection.baseUrl()
            + " antwortet für "
            + resource
            + " kein WebDAV einer Nextcloud; bitte die Adresse prüfen.");
  }

  private NextcloudAccessException failure(IOException e, String resource) {
    String host = connection.baseUrl().getHost();
    if (e instanceof TargetAddressValidator.TargetAddressBlockedException) {
      return new NextcloudAccessException.Unreachable(e.getMessage() + " " + ALLOWLIST_HINT);
    }
    if (e instanceof RedirectFollowingFetcher.RedirectRejectedException rejected) {
      return new NextcloudAccessException.Unreachable(
          rejected.userMessage() + " (beim Abruf von " + resource + ").");
    }
    if (e instanceof HttpTimeoutException) {
      return new NextcloudAccessException(
          "Nextcloud unter " + host + " hat nicht rechtzeitig geantwortet (" + resource + ").");
    }
    if (e instanceof ConnectException || e instanceof UnknownHostException) {
      return new NextcloudAccessException.Unreachable(
          "Nextcloud unter " + host + " ist nicht erreichbar.");
    }
    if (e instanceof SSLException) {
      return new NextcloudAccessException.Unreachable(
          "TLS-Verbindung zu " + host + " fehlgeschlagen (Zertifikat nicht vertrauenswürdig?).");
    }
    return new NextcloudAccessException(
        "Nextcloud unter "
            + host
            + " ist nicht erreichbar ("
            + e.getClass().getSimpleName()
            + ", "
            + resource
            + ").");
  }

  @Override
  public void close() {
    httpClient.close();
  }
}
