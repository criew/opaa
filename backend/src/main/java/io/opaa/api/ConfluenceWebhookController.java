package io.opaa.api;

import io.opaa.indexing.source.confluence.webhook.ConfluenceWebhookService;
import io.opaa.indexing.source.confluence.webhook.ConfluenceWebhookSignature;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint a Confluence instance (or an Automation rule) calls into OPAA (#1140). Reachable
 * without a session - the sender has none - and permitted explicitly in both security chains; the
 * request authenticates itself with the library's webhook secret instead, checked in {@link
 * ConfluenceWebhookService#accept}. The body is taken as raw bytes so a Data Center signature is
 * verified over exactly what was sent.
 */
@RestController
public class ConfluenceWebhookController {

  private final ConfluenceWebhookService webhookService;

  public ConfluenceWebhookController(ConfluenceWebhookService webhookService) {
    this.webhookService = webhookService;
  }

  @PostMapping(value = "/api/v1/libraries/{libraryId}/confluence-webhook", consumes = "*/*")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void receive(
      @PathVariable UUID libraryId,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = ConfluenceWebhookSignature.HUB_SIGNATURE_HEADER, required = false)
          String hubSignature,
      @RequestHeader(value = ConfluenceWebhookSignature.SHARED_SECRET_HEADER, required = false)
          String sharedSecret) {
    webhookService.accept(libraryId, body, hubSignature, sharedSecret);
  }
}
