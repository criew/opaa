package io.opaa.indexing.source.dropbox;

import static io.opaa.common.PropertyDefaults.positiveOr;
import static java.util.Objects.requireNonNullElse;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Addresses and operational bounds of the Dropbox connector. Every value falls back to its default
 * when absent or zero. The addresses are Dropbox's own; an operator changes them only to point the
 * connector at a stand-in for tests, never per library.
 *
 * @param apiBase the RPC host, the target the access token is issued for; default {@value
 *     #API_BASE}
 * @param contentBase the content host downloads and exports come from; default {@value
 *     #CONTENT_BASE}
 * @param authorizationEndpoint where a person consents; default {@value #AUTHORIZATION_ENDPOINT}
 * @param tokenEndpoint where codes and refresh tokens are exchanged; default {@value
 *     #TOKEN_ENDPOINT}
 * @param revocationEndpoint where a discarded token is revoked; default {@value
 *     #REVOCATION_ENDPOINT}
 * @param pageSize entries per {@code list_folder} page, at most {@value #MAX_PAGE_SIZE}
 * @param maxFileSizeBytes the bound of one download or export, applied while streaming; default 50
 *     MiB
 * @param requestTimeout per request; default 30 seconds
 * @param downloadTimeout the whole of one download or export; default 10 minutes
 * @param maxRetries retries of a throttled request ({@code 429} with {@code Retry-After}); default
 *     5, negative turns them off
 * @param maxRetryWait the longest wait a {@code Retry-After} may ask for; default 5 minutes
 * @param requestBudgetPerRun requests one run may send before it ends as truncated; default 20 000
 * @param maxFilesPerRun files one full sync may list before it fails asking for narrower folders;
 *     default 1 000 000
 * @param downloadConcurrency downloads in flight per run; default 2
 * @param fullSyncInterval the instance-wide rhythm of the full sync; default 7 days
 */
@ConfigurationProperties(prefix = "opaa.indexing.dropbox")
public record DropboxProperties(
    URI apiBase,
    URI contentBase,
    URI authorizationEndpoint,
    URI tokenEndpoint,
    URI revocationEndpoint,
    int pageSize,
    long maxFileSizeBytes,
    Duration requestTimeout,
    Duration downloadTimeout,
    Integer maxRetries,
    Duration maxRetryWait,
    int requestBudgetPerRun,
    int maxFilesPerRun,
    int downloadConcurrency,
    Duration fullSyncInterval) {

  public static final String API_BASE = "https://api.dropboxapi.com";
  public static final String CONTENT_BASE = "https://content.dropboxapi.com";
  public static final String AUTHORIZATION_ENDPOINT = "https://www.dropbox.com/oauth2/authorize";
  public static final String TOKEN_ENDPOINT = "https://api.dropboxapi.com/oauth2/token";
  public static final String REVOCATION_ENDPOINT = "https://api.dropboxapi.com/2/auth/token/revoke";
  public static final int MAX_PAGE_SIZE = 2000;

  public DropboxProperties {
    apiBase = apiBase == null ? URI.create(API_BASE) : withoutTrailingSlash(apiBase);
    contentBase =
        contentBase == null ? URI.create(CONTENT_BASE) : withoutTrailingSlash(contentBase);
    authorizationEndpoint =
        requireNonNullElse(authorizationEndpoint, URI.create(AUTHORIZATION_ENDPOINT));
    tokenEndpoint = requireNonNullElse(tokenEndpoint, URI.create(TOKEN_ENDPOINT));
    revocationEndpoint = requireNonNullElse(revocationEndpoint, URI.create(REVOCATION_ENDPOINT));
    pageSize = Math.min(positiveOr(pageSize, MAX_PAGE_SIZE), MAX_PAGE_SIZE);
    maxFileSizeBytes = positiveOr(maxFileSizeBytes, 50L * 1024 * 1024);
    requestTimeout = positiveOr(requestTimeout, Duration.ofSeconds(30));
    downloadTimeout = positiveOr(downloadTimeout, Duration.ofMinutes(10));
    maxRetries = requireNonNullElse(maxRetries, 5);
    maxRetryWait = positiveOr(maxRetryWait, Duration.ofMinutes(5));
    requestBudgetPerRun = positiveOr(requestBudgetPerRun, 20_000);
    maxFilesPerRun = positiveOr(maxFilesPerRun, 1_000_000);
    downloadConcurrency = positiveOr(downloadConcurrency, 2);
    fullSyncInterval = positiveOr(fullSyncInterval, Duration.ofDays(7));
  }

  public static DropboxProperties defaults() {
    return new DropboxProperties(
        null, null, null, null, null, 0, 0, null, null, null, null, 0, 0, 0, null);
  }

  /** The defaults with every Dropbox address on {@code base}, as a stand-in serves them. */
  public static DropboxProperties at(URI base) {
    String root = withoutTrailingSlash(base).toString();
    return new DropboxProperties(
        URI.create(root),
        URI.create(root),
        URI.create(root + "/oauth2/authorize"),
        URI.create(root + "/oauth2/token"),
        URI.create(root + "/2/auth/token/revoke"),
        0,
        0,
        null,
        null,
        null,
        null,
        0,
        0,
        0,
        null);
  }

  private static URI withoutTrailingSlash(URI uri) {
    String text = uri.toString();
    return text.endsWith("/") ? URI.create(text.substring(0, text.length() - 1)) : uri;
  }
}
