package io.opaa.indexing.source;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The sign-in "OAuth" a connector offers: the authorization code grant with PKCE (RFC 6749, 4.1;
 * RFC 7636) against {@code authorization} and {@code token}, renewed with the refresh token at
 * {@code token} and revoked as {@code revocation} says. The profile's client id and secret prove
 * the client as {@code clientAuth} names; the connector gets the access token alone. {@code
 * defaultScopes} stand where the profile names none, {@code authorizationParams} are added to every
 * authorization request (such as {@code token_access_type=offline}).
 */
public record OAuthAuth(
    Endpoint authorization,
    Endpoint token,
    Revocation revocation,
    String defaultScopes,
    Map<String, String> authorizationParams,
    ClientAuthentication clientAuth)
    implements SignInDetails {

  /** The parameters of an authorization request the sign-in sets itself. */
  public static final Set<String> RESERVED_PARAMS =
      Set.of(
          "response_type",
          "client_id",
          "redirect_uri",
          "scope",
          "state",
          "code_challenge",
          "code_challenge_method",
          "resource");

  public OAuthAuth {
    Objects.requireNonNull(authorization, "authorization");
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(revocation, "revocation");
    Objects.requireNonNull(clientAuth, "clientAuth");
    if (defaultScopes != null && defaultScopes.isBlank()) {
      defaultScopes = null;
    }
    authorizationParams = authorizationParams == null ? Map.of() : Map.copyOf(authorizationParams);
    for (String name : authorizationParams.keySet()) {
      if (RESERVED_PARAMS.contains(name)) {
        throw new IllegalArgumentException(
            "the authorization request sets " + name + " itself, never a connector");
      }
    }
  }

  /** Whether any endpoint of this sign-in is taken from the profile. */
  public boolean endpointsFromProfile() {
    return authorization instanceof Endpoint.FromProfile
        || token instanceof Endpoint.FromProfile
        || revocation.endpoint() instanceof Endpoint.FromProfile;
  }
}
