package io.opaa.externalaccess;

import java.util.Optional;
import java.util.UUID;

/**
 * What the channel's quota and mass-retrieval alarm need to know about the service account behind a
 * token (ADR-0035, Entscheidung 7): its hour window is the account's, shared by all its tokens, and
 * an alarm names the account. The accounts themselves live in {@code io.opaa.externalaccess.token},
 * which implements this seam.
 */
public interface ServiceAccountTokens {

  /** The service account {@code accessTokenId} belongs to; empty for a personal token. */
  Optional<ServiceAccountOfToken> serviceAccountOf(UUID accessTokenId);

  /**
   * @param rateLimitPerHour the account's own quota, or {@code null} while the channel setting
   *     applies
   */
  record ServiceAccountOfToken(UUID id, String name, Integer rateLimitPerHour) {}
}
