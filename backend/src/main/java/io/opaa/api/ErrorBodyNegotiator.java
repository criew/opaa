package io.opaa.api;

import io.opaa.api.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

/**
 * Decides whether an {@link ErrorResponse} body may be attached, given the caller's {@code Accept}
 * header (#1780). The criterion is the very compatibility test {@code
 * AbstractMessageConverterMethodProcessor} runs, so the two cannot disagree.
 *
 * <p>The envelope is only ever written by the Jackson converter, hence these two producible types;
 * that holds as long as no mapping narrows them with {@code produces=}, which none does today.
 */
class ErrorBodyNegotiator {

  private static final List<MediaType> WRITABLE_TYPES =
      List.of(MediaType.APPLICATION_JSON, MediaType.parseMediaType("application/*+json"));

  boolean acceptsErrorBody(HttpServletRequest request) {
    return acceptsErrorBody(Collections.list(request.getHeaders(HttpHeaders.ACCEPT)));
  }

  /**
   * An absent or empty {@code Accept} means {@code *}/{@code *} and takes the body, mirroring
   * {@code HeaderContentNegotiationStrategy}; an unparsable one takes none, mirroring the same
   * processor's own decision to drop the body of a 4xx/5xx it cannot negotiate.
   */
  boolean acceptsErrorBody(List<String> acceptHeaderValues) {
    List<MediaType> acceptable;
    try {
      acceptable = MediaType.parseMediaTypes(acceptHeaderValues);
    } catch (InvalidMediaTypeException ex) {
      return false;
    }
    if (acceptable.isEmpty()) {
      return true;
    }
    return acceptable.stream()
        .anyMatch(requested -> WRITABLE_TYPES.stream().anyMatch(requested::isCompatibleWith));
  }
}
