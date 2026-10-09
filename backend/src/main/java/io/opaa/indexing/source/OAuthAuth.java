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
 * authorization request (such as {@code token_access_type=offline}). {@code issuer} is the
 * authorization server's issuer the response's {@code iss} must not contradict (RFC 9207), {@code
 * null} where none is known; {@code issuerParameterSupported} says it announced {@code
 * authorization_response_iss_parameter_supported}, so every response must name it.
 */
public record OAuthAuth(
    Endpoint authorization,
    Endpoint token,
    Revocation revocation,
    String defaultScopes,
    Map<String, String> authorizationParams,
    ClientAuthentication clientAuth,
    String issuer,
    boolean issuerParameterSupported)
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
    if (issuer != null && issuer.isBlank()) {
      issuer = null;
    }
    if (issuerParameterSupported && issuer == null) {
      throw new IllegalArgumentException("an announced iss parameter needs the issuer it names");
    }
    for (String name : authorizationParams.keySet()) {
      if (RESERVED_PARAMS.contains(name)) {
        throw new IllegalArgumentException(
            "the authorization request sets " + name + " itself, never a connector");
      }
    }
  }

  /** A sign-in without a known issuer, whose response is not compared (RFC 9207). */
  public OAuthAuth(
      Endpoint authorization,
      Endpoint token,
      Revocation revocation,
      String defaultScopes,
      Map<String, String> authorizationParams,
      ClientAuthentication clientAuth) {
    this(
        authorization,
        token,
        revocation,
        defaultScopes,
        authorizationParams,
        clientAuth,
        null,
        false);
  }

  /** Whether any endpoint of this sign-in is taken from the profile. */
  public boolean endpointsFromProfile() {
    return authorization instanceof Endpoint.FromProfile
        || token instanceof Endpoint.FromProfile
        || revocation.endpoint() instanceof Endpoint.FromProfile;
  }
}
