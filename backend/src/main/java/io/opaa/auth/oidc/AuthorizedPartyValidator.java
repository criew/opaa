package io.opaa.auth.oidc;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects a token whose {@code azp} (authorized party) names a client other than the provider's own
 * (ADR-0025, Entscheidung 1): with a public client the only thing that stops a token the same
 * provider issued for a <em>different</em> application from being accepted here. A token without
 * {@code azp} passes - the claim is optional, and {@code aud} is deliberately not checked.
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
    if (authorizedParty == null || authorizedParty.equals(clientId)) {
      return OAuth2TokenValidatorResult.success();
    }
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN,
            "The azp claim names a different client than this provider's client id",
            null));
  }
}
