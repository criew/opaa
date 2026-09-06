package io.opaa.auth;

import io.opaa.auth.oidc.OidcClaimMapping;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * What a token says about its bearer, read through the provider's {@link OidcClaimMapping}
 * (ADR-0025, Entscheidung 4): the address, the display name (the configured claim, then {@code
 * preferred_username}, else none - a raw subject is no display name), and the role and group values
 * the provider's mapping points at - empty lists when the mapping names no such claim.
 */
public record TokenClaims(
    String subject,
    String issuer,
    String email,
    String displayName,
    List<String> roles,
    List<String> groups) {

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
        mapping.rolesClaim() == null ? List.of() : ClaimPaths.strings(claims, mapping.rolesClaim()),
        mapping.groupsClaim() == null
            ? List.of()
            : ClaimPaths.strings(claims, mapping.groupsClaim()));
  }
}
