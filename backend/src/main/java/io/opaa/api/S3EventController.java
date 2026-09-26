package io.opaa.api;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.source.PushIntakeHandler;
import io.opaa.indexing.source.SourceConnectorRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint an object store (MinIO, Ceph RGW, an EventBridge API destination) calls into
 * OPAA (ADR-0027, Entscheidung 6). Reachable without a session through its own security chain
 * ({@code io.opaa.auth.S3EventSecurityConfig}); the request authenticates itself with the library's
 * event token, checked by the push intake of its connector. The body is read through the same bound
 * as the Confluence webhook's, before anything else.
 */
@RestController
public class S3EventController {

  private final SourceConnectorRegistry connectors;

  public S3EventController(SourceConnectorRegistry connectors) {
    this.connectors = connectors;
  }

  @PostMapping(value = "/api/v1/libraries/{libraryId}/s3-events", consumes = "*/*")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void receive(@PathVariable UUID libraryId, HttpServletRequest request) throws IOException {
    PushIntakeHandler handler = connectors.pushIntakeHandler(DocumentSourceType.S3);
    handler.acceptNotification(
        libraryId,
        ConfluenceWebhookController.readBounded(request),
        name -> ConfluenceWebhookController.joinedHeader(request, name));
  }
}
