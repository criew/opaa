package io.opaa.search;

import io.opaa.common.TooManyRequestsException;
import java.util.UUID;

/**
 * The per-token quota every read of this package counts against (ADR-0035). Exactly one
 * implementation exists, in the external-access package; there is no fallback, for the reason
 * {@link SearchScopeSource} gives.
 */
public interface AccessTokenQuota {

  /**
   * Counts one request of {@code accessTokenId}; throws {@link TooManyRequestsException} once its
   * window is full. A {@code null} token is a signed-in person and passes untouched.
   */
  void requireWithinQuota(UUID accessTokenId);
}
