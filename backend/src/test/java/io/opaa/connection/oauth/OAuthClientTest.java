package io.opaa.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.connection.profile.ClientRegistration;
import io.opaa.connection.profile.ProfileEndpoints;
import io.opaa.indexing.source.ClientAuthentication;
import io.opaa.indexing.source.Endpoint;
import io.opaa.indexing.source.OAuthAuth;
import io.opaa.indexing.source.Revocation;
import io.opaa.indexing.source.SignInRejectedException;
import io.opaa.indexing.source.SourceCredentialsException;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.FakeAuthorizationServer;
import io.opaa.test.FakeAuthorizationServer.Request;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The OAuth client against a fake authorization server: the authorization request with PKCE, the
 * code exchange bound to verifier and redirect, renewal with rotation and its refusal, revocation
 * by RFC 7009 and by bearer, and endpoints a profile names - with no secret in any message.
 */
class OAuthClientTest {

  private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");
  private static final URI REDIRECT = URI.create("https://opaa.example.org/connections/callback");
  private static final String SECRET = "client-geheimnis";

  private final FakeAuthorizationServer server = new FakeAuthorizationServer();
  private final OAuthClient client = new OAuthClient(TargetAddressValidator.disabled());

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void theCodeIsExchangedOnlyWithItsVerifierAndRedirect() {
    String verifier = "v".repeat(43);
    URI url =
        client.authorizationUrl(
            registration(ProfileEndpoints.NONE),
            fixed(new Revocation.None()),
            "zustand",
            ConnectionAuthorizationService.challengeOf(verifier),
            REDIRECT);
    assertThat(query(url))
        .containsEntry("prompt", "consent")
        .containsEntry("scope", "files.read offline_access")
        .containsEntry("code_challenge_method", "S256");

    String code = server.approve(url);
    assertThatThrownBy(
            () ->
                client.exchange(
                    registration(ProfileEndpoints.NONE),
                    fixed(new Revocation.None()),
                    code,
                    "w".repeat(43),
                    REDIRECT,
                    NOW))
        .isInstanceOf(SourceCredentialsException.class)
        .isNotInstanceOf(SignInRejectedException.class)
        .hasMessageNotContaining(code);

    String second = server.approve(url);
    OAuthClient.Grant grant =
        client.exchange(
            registration(ProfileEndpoints.NONE),
            fixed(new Revocation.None()),
            second,
            verifier,
            REDIRECT,
            NOW);

    assertThat(grant.accessToken()).isEqualTo(server.lastToken());
    assertThat(grant.refreshToken()).isEqualTo(server.lastRefreshToken());
    assertThat(grant.accessTokenExpiresAt()).isEqualTo(NOW.plusSeconds(3600));
    assertThat(grant.toString()).doesNotContain(grant.accessToken(), grant.refreshToken());
  }

  @Test
  void aRenewalRotatesAndARefusedRefreshTokenIsRejected() {
    OAuthClient.Grant grant = consent();
    server.refreshLifetime(Duration.ofDays(30));

    OAuthClient.Grant renewed =
        client.refresh(
            registration(ProfileEndpoints.NONE),
            fixed(new Revocation.None()),
            grant.refreshToken(),
            NOW);

    assertThat(renewed.refreshToken()).isNotEqualTo(grant.refreshToken());
    assertThat(renewed.refreshTokenExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
    assertThatThrownBy(
            () ->
                client.refresh(
                    registration(ProfileEndpoints.NONE),
                    fixed(new Revocation.None()),
                    grant.refreshToken(),
                    NOW))
        .isInstanceOf(SignInRejectedException.class)
        .hasMessageNotContaining(grant.refreshToken());

    server.rejectWith(401, "invalid_client");
    assertThatThrownBy(
            () ->
                client.refresh(
                    registration(ProfileEndpoints.NONE),
                    fixed(new Revocation.None()),
                    renewed.refreshToken(),
                    NOW))
        .as("a refused registration is the administration's, not the person's grant")
        .isInstanceOf(SourceCredentialsException.class)
        .isNotInstanceOf(SignInRejectedException.class)
        .hasMessageContaining("Systemverwaltung")
        .hasMessageNotContaining(SECRET);
  }

  @Test
  void revocationSendsTheRefreshTokenOrTheAccessTokenAsBearer() {
    OAuthClient.Grant grant = consent();

    assertThat(
            client.revoke(
                registration(ProfileEndpoints.NONE),
                fixed(new Revocation.Rfc7009(new Endpoint.Fixed(server.revocationEndpoint()))),
                grant.refreshToken(),
                grant.accessToken()))
        .isTrue();
    assertThat(
            client.revoke(
                registration(ProfileEndpoints.NONE),
                fixed(
                    new Revocation.BearerPost(
                        new Endpoint.Fixed(server.bearerRevocationEndpoint()))),
                grant.refreshToken(),
                grant.accessToken()))
        .isTrue();
    assertThat(
            client.revoke(
                registration(ProfileEndpoints.NONE),
                fixed(new Revocation.None()),
                grant.refreshToken(),
                grant.accessToken()))
        .isFalse();

    assertThat(server.revocations()).hasSize(2);
    Request rfc7009 = server.revocations().get(0);
    assertThat(rfc7009.form())
        .containsEntry("token", grant.refreshToken())
        .containsEntry("token_type_hint", "refresh_token");
    assertThat(rfc7009.authorization()).startsWith("Basic ");
    assertThat(server.revocations().get(1).authorization())
        .isEqualTo("Bearer " + grant.accessToken());
    assertThat(server.isLive(grant.refreshToken())).isFalse();
  }

  /**
   * A provider that takes the revocation but never answers costs one call its time limit, not more:
   * the revocation gives up, reports failure and throws nothing.
   */
  @Test
  void aRevocationThatIsNeverAnsweredEndsAtItsTimeLimit() {
    OAuthClient.Grant grant = consent();
    OAuthClient bounded =
        new OAuthClient(TargetAddressValidator.disabled(), Duration.ofMillis(300));
    server.holdRevocations();
    try {
      boolean revoked =
          assertTimeoutPreemptively(
              Duration.ofSeconds(30),
              () ->
                  bounded.revoke(
                      registration(ProfileEndpoints.NONE),
                      fixed(
                          new Revocation.Rfc7009(new Endpoint.Fixed(server.revocationEndpoint()))),
                      grant.refreshToken(),
                      grant.accessToken()));

      assertThat(revoked).isFalse();
      assertThat(server.revocations()).as("the provider got the request").hasSize(1);
    } finally {
      server.releaseRevocations();
    }
  }

  @Test
  void endpointsTheProfileNamesAreUsedAndAMissingOneIsTheAdministrations() {
    OAuthAuth fromProfile =
        new OAuthAuth(
            new Endpoint.FromProfile(),
            new Endpoint.FromProfile(),
            new Revocation.Rfc7009(new Endpoint.FromProfile()),
            "openid",
            Map.of(),
            ClientAuthentication.CLIENT_SECRET_POST);
    ProfileEndpoints named =
        new ProfileEndpoints(
            server.authorizationEndpoint() + "?realm=haus",
            server.tokenEndpoint().toString(),
            server.revocationEndpoint().toString());

    URI url = client.authorizationUrl(registration(named), fromProfile, "s", "c", REDIRECT);
    String code = server.approve(url);

    assertThat(url.toString()).startsWith(server.authorizationEndpoint() + "?realm=haus&");
    assertThatThrownBy(
            () ->
                client.exchange(
                    registration(ProfileEndpoints.NONE), fromProfile, code, "v", REDIRECT, NOW))
        .isInstanceOf(SourceCredentialsException.class)
        .hasMessageContaining("Systemverwaltung");
    assertThat(server.requests()).isEmpty();
  }

  @Test
  void anUnreachableEndpointIsAFailureWithoutATokenInItsMessage() {
    OAuthClient.Grant grant = consent();
    server.unreachable(true);

    assertThatThrownBy(
            () ->
                client.refresh(
                    registration(ProfileEndpoints.NONE),
                    fixed(new Revocation.None()),
                    grant.refreshToken(),
                    NOW))
        .isInstanceOf(SourceCredentialsException.class)
        .isNotInstanceOf(SignInRejectedException.class)
        .hasMessageNotContaining(grant.refreshToken());
  }

  private OAuthClient.Grant consent() {
    String verifier = "a".repeat(43);
    URI url =
        client.authorizationUrl(
            registration(ProfileEndpoints.NONE),
            fixed(new Revocation.None()),
            "zustand",
            ConnectionAuthorizationService.challengeOf(verifier),
            REDIRECT);
    return client.exchange(
        registration(ProfileEndpoints.NONE),
        fixed(new Revocation.None()),
        server.approve(url),
        verifier,
        REDIRECT,
        NOW);
  }

  private OAuthAuth fixed(Revocation revocation) {
    return new OAuthAuth(
        new Endpoint.Fixed(server.authorizationEndpoint()),
        new Endpoint.Fixed(server.tokenEndpoint()),
        revocation,
        "files.read offline_access",
        Map.of("prompt", "consent"),
        ClientAuthentication.CLIENT_SECRET_BASIC);
  }

  private static ClientRegistration registration(ProfileEndpoints endpoints) {
    return new ClientRegistration(
        UUID.randomUUID(),
        ConnectionAuthMethod.OAUTH,
        "opaa",
        SECRET,
        null,
        null,
        null,
        null,
        null,
        false,
        endpoints,
        0);
  }

  private static Map<String, String> query(URI url) {
    Map<String, String> query = new LinkedHashMap<>();
    for (String pair : url.getRawQuery().split("&")) {
      int equals = pair.indexOf('=');
      query.put(
          URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
    }
    return query;
  }
}
