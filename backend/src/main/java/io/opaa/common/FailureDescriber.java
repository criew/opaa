package io.opaa.common;

import io.opaa.api.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Describes a failure with the envelope the application's error handling would answer it with, for
 * a response that is already under way and can carry the error only inside its body.
 */
public interface FailureDescriber {

  /** The same message, code and status as for a response not yet begun, without its headers. */
  ErrorResponse describe(Throwable failure, HttpServletRequest request);
}
