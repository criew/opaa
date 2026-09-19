package io.opaa.api;

import io.opaa.api.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

/**
 * Decides whether an {@link ErrorResponse} body may be attached, given the caller's {@code Accept}
 * header (#1780): the same compatibility test {@code AbstractMessageConverterMethodProcessor} runs,
 * against the types the Jackson converter writes for the envelope.
 *
 * <p>Pre-check and writer agree while two conditions hold - no other registered converter writes
 * {@link ErrorResponse} ({@code Jaxb2RootElementHttpMessageConverter} is registered and declines
 * only for want of XML annotations on the generated DTO), and the matched mapping declares no
 * narrower {@code produces=}. No mapping in {@code io.opaa} does. Actuator's {@code
 * /actuator/prometheus} does, and since a {@code @RestControllerAdvice} without a selector covers
 * its mappings too, that is the one place where the two would part ways.
 */
class ErrorBodyNegotiator {

  private static final List<MediaType> WRITABLE_TYPES =
      List.of(MediaType.APPLICATION_JSON, MediaType.parseMediaType("application/*+json"));

  boolean acceptsErrorBody(HttpServletRequest request) {
    // A null enumeration violates the servlet contract, but an NPE raised here would re-enter the
    // very path this class closes; an absent Accept is the harmless reading of it.
    Enumeration<String> acceptHeaders = request.getHeaders(HttpHeaders.ACCEPT);
    return acceptsErrorBody(acceptHeaders == null ? List.of() : Collections.list(acceptHeaders));
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
