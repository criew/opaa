package io.opaa.indexing.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the synchronous remote-content proxy path (#747/#748 review, finding 1) -
 * {@code GET /api/v1/documents/{documentId}/content} for a {@code HTTP_DIRECTORY}/{@code RSS_FEED}
 * document, which streams the original from its stored source URL rather than the local disk every
 * other {@code sourceType} serves from.
 *
 * <p>Deliberately its own, smaller property block rather than reusing {@link
 * io.opaa.knowledge.UploadProperties#maxFileSize()}/its 120s indexing-run timeout (#748 review,
 * finding 1): this path is a synchronous, VIEWER-reachable, click-driven request against an
 * outbound connection this system does not control, not a background indexing run or a bounded
 * local-disk read - the same reasoning {@code ConnectorChecks#REQUEST_TIMEOUT} already applies to
 * its own synchronous probe.
 *
 * @param maxBytes maximum number of bytes streamed from the remote source per request, enforced
 *     while streaming (see {@code io.opaa.sourceaccess.BoundedDownloader#downloadStreaming}), not
 *     by buffering the whole response first. Default 20 MiB (20 971 520) - generous for a typical
 *     Dienstanweisung PDF while bounding how long a single click can hold a connection to an
 *     unbounded remote body open.
 * @param timeoutSeconds timeout for each hop of the proxied fetch (including redirects) up to its
 *     answer. Default 20s - well under the background-indexing timeouts, since a caller waiting on
 *     this endpoint is a human watching a spinner, not an unattended crawl.
 * @param transferTimeoutSeconds bound of the whole body from the answer's start, however slowly it
 *     trickles in and however slowly the browser reads it. Unset ({@code <= 0}) it is 120s - about
 *     1.4 Mbit/s for a body at the default {@code maxBytes} - or {@code timeoutSeconds} if that is
 *     longer; a value set explicitly below {@code timeoutSeconds} fails the start.
 */
@ConfigurationProperties(prefix = "opaa.documents.remote-content")
public record RemoteContentProperties(
    long maxBytes, int timeoutSeconds, int transferTimeoutSeconds) {

  public RemoteContentProperties {
    if (maxBytes <= 0) {
      maxBytes = 20L * 1024 * 1024;
    }
    if (timeoutSeconds <= 0) {
      timeoutSeconds = 20;
    }
    if (transferTimeoutSeconds <= 0) {
      transferTimeoutSeconds = Math.max(120, timeoutSeconds);
    }
    if (transferTimeoutSeconds < timeoutSeconds) {
      throw new IllegalArgumentException(
          "opaa.documents.remote-content.transfer-timeout-seconds ("
              + transferTimeoutSeconds
              + ") must not be shorter than timeout-seconds ("
              + timeoutSeconds
              + ")");
    }
  }
}
