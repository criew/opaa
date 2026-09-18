package io.opaa.externalaccess.token;

import io.opaa.library.LibraryExternalAccessTokenCounter;
import java.time.Clock;
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
  private final Clock clock;

  ExternalAccessTokenCounter(ExternalAccessTokenRepository tokens, Clock clock) {
    this.tokens = tokens;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public long countActiveTokensFor(UUID libraryId) {
    return tokens.countActiveContainingLibrary(libraryId, clock.instant());
  }
}
