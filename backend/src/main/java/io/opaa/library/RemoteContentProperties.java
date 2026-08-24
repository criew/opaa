package io.opaa.library;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the synchronous remote-content proxy path (#747/#748 review, finding 1) -
 * {@code GET /api/v1/documents/{documentId}/content} for a {@code HTTP_DIRECTORY}/{@code RSS_FEED}
 * document, which streams the original from its stored source URL rather than the local disk every
 * other {@code sourceType} serves from.
 *
 * <p>Deliberately its own, smaller property block rather than reusing {@link
 * UploadProperties#maxFileSize()}/its 120s indexing-run timeout (#748 review, finding 1): this path
 * is a synchronous, VIEWER-reachable, click-driven request against an outbound connection this
 * system does not control, not a background indexing run or a bounded local-disk read - the same
 * reasoning {@code SourceConnectionTestService#REQUEST_TIMEOUT} already applies to its own
 * synchronous probe.
 *
 * @param maxBytes maximum number of bytes streamed from the remote source per request, enforced
 *     while streaming (see {@code io.opaa.sourceaccess.BoundedDownloader#downloadStreaming}), not
 *     by buffering the whole response first. Default 20 MiB (20 971 520) - generous for a typical
 *     Dienstanweisung PDF while bounding how long a single click can hold a connection to an
 *     unbounded remote body open.
 * @param timeoutSeconds per-request timeout for each hop of the proxied fetch (including
 *     redirects). Default 20s - well under {@code
 *     io.opaa.sourceaccess.BoundedDownloader#downloadBounded}'s 120s background-indexing timeout,
 *     since a caller waiting on this endpoint is a human watching a spinner, not an unattended
 *     crawl.
 */
@ConfigurationProperties(prefix = "opaa.documents.remote-content")
public record RemoteContentProperties(long maxBytes, int timeoutSeconds) {

  public RemoteContentProperties {
    if (maxBytes <= 0) {
      maxBytes = 20L * 1024 * 1024;
    }
    if (timeoutSeconds <= 0) {
      timeoutSeconds = 20;
    }
  }
}
