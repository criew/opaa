package io.opaa.api;

import io.opaa.indexing.source.s3.events.S3EventAuthentication;
import io.opaa.indexing.source.s3.events.S3EventService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint an object store (MinIO, Ceph RGW, an EventBridge API destination) calls into
 * OPAA (ADR-0027, Entscheidung 6). Reachable without a session through its own security chain
 * ({@code io.opaa.auth.S3EventSecurityConfig}); the request authenticates itself with the library's
 * event token, checked in {@link S3EventService#accept}. The body is read through the same bound as
 * the Confluence webhook's, before anything else.
 */
@RestController
public class S3EventController {

  private final S3EventService eventService;

  public S3EventController(S3EventService eventService) {
    this.eventService = eventService;
  }

  @PostMapping(value = "/api/v1/libraries/{libraryId}/s3-events", consumes = "*/*")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void receive(
      @PathVariable UUID libraryId,
      HttpServletRequest request,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestHeader(value = S3EventAuthentication.SHARED_SECRET_HEADER, required = false)
          String sharedSecret)
      throws IOException {
    eventService.accept(
        libraryId, ConfluenceWebhookController.readBounded(request), authorization, sharedSecret);
  }
}
