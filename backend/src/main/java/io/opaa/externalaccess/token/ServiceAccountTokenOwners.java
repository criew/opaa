package io.opaa.externalaccess.token;

import io.opaa.externalaccess.ServiceAccountTokens;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Answers {@link ServiceAccountTokens} from the token and account tables, per call. */
@Component
class ServiceAccountTokenOwners implements ServiceAccountTokens {

  private final ServiceAccountTokenRepository tokens;

  ServiceAccountTokenOwners(ServiceAccountTokenRepository tokens) {
    this.tokens = tokens;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ServiceAccountOfToken> serviceAccountOf(UUID accessTokenId) {
    if (accessTokenId == null) {
      return Optional.empty();
    }
    return tokens
        .findAccountOfToken(accessTokenId)
        .map(
            account ->
                new ServiceAccountOfToken(
                    account.getId(), account.getName(), account.getRateLimitPerHour()));
  }
}
