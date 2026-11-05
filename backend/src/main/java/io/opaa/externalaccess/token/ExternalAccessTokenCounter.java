package io.opaa.externalaccess.token;

import io.opaa.library.LibraryExternalAccessTokenCounter;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "how many access tokens carry this library right now" for the release view of the
 * responsible person. One aggregate query, no person is loaded and none could be.
 */
@Component
class ExternalAccessTokenCounter implements LibraryExternalAccessTokenCounter {

  private final ExternalAccessTokenRepository tokens;
  private final ServiceAccountTokenRepository serviceAccountTokens;
  private final Clock clock;

  ExternalAccessTokenCounter(
      ExternalAccessTokenRepository tokens,
      ServiceAccountTokenRepository serviceAccountTokens,
      Clock clock) {
    this.tokens = tokens;
    this.serviceAccountTokens = serviceAccountTokens;
    this.clock = clock;
  }

  /**
   * Personal tokens selecting the library plus live tokens of active service accounts with a
   * consent in force for it - so the library administration sees a connected Fachverfahren.
   */
  @Override
  @Transactional(readOnly = true)
  public long countActiveTokensFor(UUID libraryId) {
    Instant now = clock.instant();
    return tokens.countActiveContainingLibrary(libraryId, now)
        + serviceAccountTokens.countActiveForConsentedLibrary(libraryId, now);
  }
}
