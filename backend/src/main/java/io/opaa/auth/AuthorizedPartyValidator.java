package io.opaa.auth;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects a token the provider issued for a <em>different</em> application (ADR-0025, Entscheidung
 * 1). A token passes when its {@code azp} is the provider's client id, or when its {@code aud}
 * contains the client id - whether {@code azp} is absent or names another client (a service client
 * declared for this one through an audience mapper). Every other token is {@code invalid_token}.
 */
final class AuthorizedPartyValidator implements OAuth2TokenValidator<Jwt> {

  static final String CLAIM = "azp";

  private final String clientId;

  AuthorizedPartyValidator(String clientId) {
    this.clientId = clientId;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    String authorizedParty = token.getClaimAsString(CLAIM);
    if (clientId.equals(authorizedParty)) {
      return OAuth2TokenValidatorResult.success();
    }
    List<String> audience = token.getAudience();
    if (audience != null && audience.contains(clientId)) {
      return OAuth2TokenValidatorResult.success();
    }
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN,
            authorizedParty == null
                ? "The token carries no azp claim and its aud does not contain this provider's"
                    + " client id"
                : "The azp claim names a different client than this provider's client id, and aud"
                    + " does not contain it either",
            null));
  }
}
