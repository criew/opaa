package io.opaa.connection.oauth;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.connection.profile.ClientRegistration;
import io.opaa.connection.profile.ProfileEndpoints;
import io.opaa.connection.token.SecretIssuer.StoredTokens;
import io.opaa.indexing.spi.ClientAuthentication;
import io.opaa.indexing.spi.Endpoint;
import io.opaa.indexing.spi.OAuthAuth;
import io.opaa.indexing.spi.Revocation;
import io.opaa.indexing.spi.SourceCredentialsException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Everything one revocation needs, taken from the registration as it stood at the discard
 * (ADR-0053, Entscheidung 10): the endpoints resolved to addresses, the client's credentials and
 * the tokens. A change or deletion of the profile afterwards does not alter the order, so a grant
 * is revoked with the registration it was issued to, by whichever instance carries it out.
 *
 * <p>The text form is JSON and carries secrets; it is only ever stored encrypted. {@link #toString}
 * shows none of them.
 */
record RevocationOrder(
    UUID profileId,
    String method,
    String clientId,
    String secret,
    String scopes,
    String subject,
    String resource,
    long version,
    boolean bearer,
    String revocationEndpoint,
    String tokenEndpoint,
    String clientAuth,
    String refreshToken,
    String accessToken,
    Long accessTokenExpiresAtMillis) {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * The order for {@code tokens} under {@code registration}.
   *
   * @throws SourceCredentialsException when the revocation endpoint cannot be resolved
   */
  static RevocationOrder of(ClientRegistration registration, OAuthAuth auth, StoredTokens tokens) {
    URI revocation =
        OAuthClient.resolve(
            auth.revocation().endpoint(), registration, ProfileEndpoints::revocation);
    String token;
    try {
      token = OAuthClient.resolve(auth.token(), registration, ProfileEndpoints::token).toString();
    } catch (SourceCredentialsException unresolvable) {
      token = null;
    }
    Instant expiresAt = tokens.accessTokenExpiresAt();
    return new RevocationOrder(
        registration.profileId(),
        registration.method().name(),
        registration.clientId(),
        registration.secret(),
        registration.scopes(),
        registration.subject(),
        registration.resource(),
        registration.version(),
        auth.revocation() instanceof Revocation.BearerPost,
        revocation.toString(),
        token,
        auth.clientAuth().name(),
        tokens.refreshToken(),
        tokens.accessToken(),
        expiresAt == null ? null : expiresAt.toEpochMilli());
  }

  /** The order read back, or {@code null} for text that is no order. */
  static RevocationOrder parse(String text) {
    if (text == null) {
      return null;
    }
    try {
      return JSON.readValue(text, RevocationOrder.class);
    } catch (JacksonException | IllegalArgumentException unreadable) {
      return null;
    }
  }

  String toText() {
    return JSON.writeValueAsString(this);
  }

  /** The registration the order revokes with; every endpoint is the address resolved before. */
  ClientRegistration registration() {
    Endpoint fromProfile = new Endpoint.FromProfile();
    OAuthAuth auth =
        new OAuthAuth(
            fromProfile,
            fromProfile,
            bearer ? new Revocation.BearerPost(fromProfile) : new Revocation.Rfc7009(fromProfile),
            null,
            Map.of(),
            ClientAuthentication.valueOf(clientAuth));
    return new ClientRegistration(
        profileId,
        ConnectionAuthMethod.valueOf(method),
        clientId,
        secret,
        null,
        scopes,
        auth,
        subject,
        false,
        new ProfileEndpoints(null, tokenEndpoint, revocationEndpoint),
        version,
        resource);
  }

  StoredTokens tokens() {
    return new StoredTokens(
        refreshToken,
        accessToken,
        accessTokenExpiresAtMillis == null
            ? null
            : Instant.ofEpochMilli(accessTokenExpiresAtMillis));
  }

  @Override
  public String toString() {
    return "RevocationOrder[profileId=" + profileId + ", bearer=" + bearer + "]";
  }
}
