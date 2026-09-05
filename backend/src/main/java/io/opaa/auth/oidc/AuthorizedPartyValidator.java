package io.opaa.auth.oidc;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects a token the provider issued for a <em>different</em> application (ADR-0025, Entscheidung
 * 1) - with a public client the only thing that stops such a token from being accepted here. A
 * token passes when its {@code azp} (authorized party) is the provider's client id, when it carries
 * no {@code azp} at all (the claim is optional), or when its {@code aud} names the client id: the
 * provider's administrator then declared the token as meant for this client (an audience mapper on
 * another client - e.g. a service client that seeds data on behalf of the application), which is
 * the same trust decision as registering the client itself. {@code aud} alone is otherwise not
 * checked.
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
    List<String> audience = token.getAudience();
    if (audience != null && audience.contains(clientId)) {
      return OAuth2TokenValidatorResult.success();
    }
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN,
            "The azp claim names a different client than this provider's client id, and aud does"
                + " not name it either",
            null));
  }
}
