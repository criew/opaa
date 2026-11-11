package io.opaa.architecture.fixture.security.rows;

import java.time.Instant;
import java.util.List;

/** Saves a loaded versioned row inside a lambda, reached only through {@link TokenStep}. */
public class RevokingStep implements TokenStep {

  private final VersionedTokenRepository tokens;

  public RevokingStep(VersionedTokenRepository tokens) {
    this.tokens = tokens;
  }

  @Override
  public void apply(VersionedToken token) {
    List.of(token)
        .forEach(
            loaded -> {
              loaded.revoke(Instant.now());
              tokens.save(loaded);
            });
  }
}
