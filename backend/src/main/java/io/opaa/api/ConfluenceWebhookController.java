package io.opaa.api;

import io.opaa.common.PayloadTooLargeException;
import io.opaa.indexing.source.confluence.webhook.ConfluenceWebhookService;
import io.opaa.indexing.source.confluence.webhook.ConfluenceWebhookSignature;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint a Confluence instance (or an Automation rule) calls into OPAA (#1140). Reachable
 * without a session - the sender has none - and permitted explicitly in both security chains; the
 * request authenticates itself with the library's webhook secret instead, checked in {@link
 * ConfluenceWebhookService#accept}. The body is read as raw bytes so a Data Center signature is
 * verified over exactly what was sent - and read through a bound of {@value #MAX_BODY_BYTES},
 * before anything else: a stranger must not be able to make the backend buffer an arbitrarily large
 * body only to be told 401 afterwards. A page or attachment notification is a few kilobytes.
 */
@RestController
public class ConfluenceWebhookController {

  static final int MAX_BODY_BYTES = 256 * 1024;

  private final ConfluenceWebhookService webhookService;

  public ConfluenceWebhookController(ConfluenceWebhookService webhookService) {
    this.webhookService = webhookService;
  }

  @PostMapping(value = "/api/v1/libraries/{libraryId}/confluence-webhook", consumes = "*/*")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void receive(
      @PathVariable UUID libraryId,
      HttpServletRequest request,
      @RequestHeader(value = ConfluenceWebhookSignature.HUB_SIGNATURE_HEADER, required = false)
          String hubSignature,
      @RequestHeader(value = ConfluenceWebhookSignature.SHARED_SECRET_HEADER, required = false)
          String sharedSecret)
      throws IOException {
    webhookService.accept(libraryId, readBounded(request), hubSignature, sharedSecret);
  }

  /** Rejects by the declared length first, then by what actually arrives (chunked senders). */
  private static byte[] readBounded(HttpServletRequest request) throws IOException {
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
