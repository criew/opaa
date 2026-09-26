package io.opaa.search;

import java.util.UUID;

/**
 * The channel-wide mass-retrieval alert every retrieval of this package feeds (ADR-0035). Exactly
 * one implementation exists, in the external-access package; there is no fallback, for the reason
 * {@link SearchScopeSource} gives.
 */
public interface MassRetrievalAlarm {

  /**
   * Counts one retrieval of the external-access channel. A {@code null} token is a signed-in person
   * and counts for nothing.
   */
  void record(UUID organizationId, UUID accessTokenId);
}
