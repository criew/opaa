package io.opaa.auth;

import io.opaa.auth.oidc.OidcClaimMapping;
import java.util.Map;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * What a token says about its bearer, read through the provider's {@link OidcClaimMapping}
 * (ADR-0025, Entscheidung 4): the address, the display name (the configured claim, then {@code
 * preferred_username}, else none - a raw subject is no display name), and the role and group values
 * the provider's mapping points at.
 *
 * <p>Neither {@link #roles()} nor {@link #groups()} is a list: a token that carries no usable claim
 * must not read like one that carries an empty claim (#1807, #1830) - an empty role list would mean
 * {@code USER} and an empty group list revokes every membership. The distinction lives in {@link
 * TokenRoles} and {@link TokenGroups}; a mapping that names no such claim yields their {@code
 * CLAIM_MISSING}.
 */
public record TokenClaims(
    String subject,
    String issuer,
    String email,
    String displayName,
    TokenRoles roles,
    TokenGroups groups) {

  static final String PREFERRED_USERNAME_CLAIM = "preferred_username";

  public static TokenClaims read(Jwt jwt, OidcClaimMapping mapping) {
    Map<String, Object> claims = jwt.getClaims();
    String displayName = ClaimPaths.string(claims, mapping.displayNameClaim());
    if (displayName == null) {
      displayName = ClaimPaths.string(claims, PREFERRED_USERNAME_CLAIM);
    }
    return new TokenClaims(
        jwt.getSubject(),
        JwtUserClaims.issuer(jwt),
        ClaimPaths.string(claims, mapping.emailClaim()),
        displayName,
        TokenRoles.read(claims, mapping.rolesClaim()),
        TokenGroups.read(claims, mapping.groupsClaim()));
  }
}
