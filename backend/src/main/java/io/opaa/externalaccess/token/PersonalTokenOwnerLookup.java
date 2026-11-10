package io.opaa.externalaccess.token;

import io.opaa.externalaccess.PersonalTokenOwners;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Answers {@link PersonalTokenOwners} from the token table, per call. */
@Component
class PersonalTokenOwnerLookup implements PersonalTokenOwners {

  private final ExternalAccessTokenRepository tokens;

  PersonalTokenOwnerLookup(ExternalAccessTokenRepository tokens) {
    this.tokens = tokens;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<UUID> ownerOf(UUID accessTokenId) {
    if (accessTokenId == null) {
      return Optional.empty();
    }
    return tokens.findById(accessTokenId).map(ExternalAccessToken::getUserId);
  }
}
