package io.opaa.api;

import io.opaa.common.PayloadTooLargeException;
import io.opaa.indexing.source.PushIntake;
import io.opaa.indexing.source.PushIntakeHandler;
import io.opaa.indexing.source.SourceConnectorRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint a Confluence instance (or an Automation rule) calls into OPAA (#1140). Reachable
 * without a session - the sender has none - and permitted explicitly in both security chains; the
 * request authenticates itself with the library's webhook secret instead, checked by the {@link
 * PushIntake#WEBHOOK_SECRET} handler of its connector. The body is read as raw bytes so a Data
 * Center signature is verified over exactly what was sent - and read through a bound of {@value
 * #MAX_BODY_BYTES}, before anything else: a stranger must not be able to make the backend buffer an
 * arbitrarily large body only to be told 401 afterwards. A page or attachment notification is a few
 * kilobytes.
 */
@RestController
public class ConfluenceWebhookController {

  /** Shared with the S3 event intake (ADR-0027, Entscheidung 6). */
  public static final int MAX_BODY_BYTES = 256 * 1024;

  private final SourceConnectorRegistry connectors;

  public ConfluenceWebhookController(SourceConnectorRegistry connectors) {
    this.connectors = connectors;
  }

  @PostMapping(value = "/api/v1/libraries/{libraryId}/confluence-webhook", consumes = "*/*")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void receive(@PathVariable UUID libraryId, HttpServletRequest request) throws IOException {
    PushIntakeHandler handler = connectors.pushIntakeHandler(PushIntake.WEBHOOK_SECRET);
    handler.acceptNotification(
        libraryId, readBounded(request), name -> joinedHeader(request, name));
  }

  /**
   * Every value of the header {@code name}, joined with {@code ","} the way Spring binds a repeated
   * header, or {@code null} without one - a correct secret next to a wrong duplicate does not
   * authenticate.
   */
  static String joinedHeader(HttpServletRequest request, String name) {
    List<String> values = Collections.list(request.getHeaders(name));
    return values.isEmpty() ? null : String.join(",", values);
  }

  /** Rejects by the declared length first, then by what actually arrives (chunked senders). */
  static byte[] readBounded(HttpServletRequest request) throws IOException {
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      throw new PayloadTooLargeException("Webhook-Nachricht zu groß");
    }
    try (InputStream in = request.getInputStream()) {
      byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
      if (body.length > MAX_BODY_BYTES) {
        throw new PayloadTooLargeException("Webhook-Nachricht zu groß");
      }
      return body;
    }
  }
}
