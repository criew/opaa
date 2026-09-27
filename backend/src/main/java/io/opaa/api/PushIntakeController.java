package io.opaa.api;

import io.opaa.common.PayloadTooLargeException;
import io.opaa.library.PushIntakeService;
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
 * The one endpoint a source calls into OPAA to push a change notification (ADR-0038). Reachable
 * without a session through its own security chain ({@code io.opaa.auth.PushIntakeSecurityConfig});
 * the library's connector authenticates the request against the library's push secret. The body is
 * read as raw bytes, so a signature is verified over exactly what was sent, and through a bound of
 * {@value #MAX_BODY_BYTES} before anything else: a stranger must not make the backend buffer an
 * arbitrarily large body only to be told 401 afterwards.
 */
@RestController
public class PushIntakeController {

  public static final int MAX_BODY_BYTES = 256 * 1024;

  private final PushIntakeService pushIntakeService;

  public PushIntakeController(PushIntakeService pushIntakeService) {
    this.pushIntakeService = pushIntakeService;
  }

  @PostMapping(value = "/api/v1/libraries/{libraryId}/push", consumes = "*/*")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void receive(@PathVariable UUID libraryId, HttpServletRequest request) throws IOException {
    pushIntakeService.accept(libraryId, readBounded(request), name -> joinedHeader(request, name));
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
      throw new PayloadTooLargeException("Benachrichtigung zu groß");
    }
    try (InputStream in = request.getInputStream()) {
      byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
      if (body.length > MAX_BODY_BYTES) {
        throw new PayloadTooLargeException("Benachrichtigung zu groß");
      }
      return body;
    }
  }
}
