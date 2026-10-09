package io.opaa.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.connection.profile.ClientRegistration;
import io.opaa.connection.profile.ProfileEndpoints;
import io.opaa.connection.profile.ProfileRegistrations;
import io.opaa.connection.token.SecretIssuer.StoredTokens;
import io.opaa.indexing.source.ClientAuthentication;
import io.opaa.indexing.source.Endpoint;
import io.opaa.indexing.source.OAuthAuth;
import io.opaa.indexing.source.Revocation;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.FakeAuthorizationServer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A revocation by bearer sends an access token that is valid when it is sent: one that has expired
 * or is about to is renewed with the refresh token first, outside any transaction, and a grant the
 * provider no longer renews is not sent at all. RFC 7009 sends the refresh token and renews
 * nothing.
 */
class ProviderTokensRevocationTest {

  private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");
  private static final URI REDIRECT = URI.create("https://opaa.example.org/connections/callback");

  private final FakeAuthorizationServer server = new FakeAuthorizationServer();
  private final ProfileRegistrations registrations = mock(ProfileRegistrations.class);
  private final ProviderTokens tokens =
      new ProviderTokens(
          registrations,
          mock(ProfileSignIn.class),
          TargetAddressValidator.disabled(),
          Clock.fixed(NOW, ZoneOffset.UTC));
  private final UUID profile = UUID.randomUUID();

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void anExpiredAccessTokenIsRenewedBeforeTheBearerRevocation() {
    OAuthClient.Grant grant = consent(bearer());

    tokens
        .revocation(
            profile,
            new StoredTokens(grant.refreshToken(), grant.accessToken(), NOW.minusSeconds(60)))
        .run();

    assertThat(server.requests("refresh_token")).hasSize(1);
    assertThat(server.revocations())
        .singleElement()
        .satisfies(
            request ->
                assertThat(request.authorization())
                    .isEqualTo("Bearer " + server.lastToken())
                    .isNotEqualTo("Bearer " + grant.accessToken()));
  }

  @Test
  void anAccessTokenAboutToExpireIsRenewedAsWell() {
    OAuthClient.Grant grant = consent(bearer());

    tokens
        .revocation(
            profile,
            new StoredTokens(grant.refreshToken(), grant.accessToken(), NOW.plusSeconds(30)))
        .run();

    assertThat(server.requests("refresh_token")).hasSize(1);
    assertThat(server.revocations().getFirst().authorization())
        .isEqualTo("Bearer " + server.lastToken());
  }

  @Test
  void aValidAccessTokenIsSentAsItIs() {
    OAuthClient.Grant grant = consent(bearer());

    tokens
        .revocation(
            profile,
            new StoredTokens(
                grant.refreshToken(), grant.accessToken(), NOW.plus(Duration.ofHours(1))))
        .run();

    assertThat(server.requests("refresh_token")).isEmpty();
    assertThat(server.revocations().getFirst().authorization())
        .isEqualTo("Bearer " + grant.accessToken());
  }

  @Test
  void aGrantTheProviderNoLongerRenewsIsNotSent() {
    OAuthClient.Grant grant = consent(bearer());
    server.rejectWith(400, "invalid_grant");

    tokens
        .revocation(
            profile,
            new StoredTokens(grant.refreshToken(), grant.accessToken(), NOW.minusSeconds(60)))
        .run();

    assertThat(server.requests("refresh_token")).hasSize(1);
    assertThat(server.revocations()).isEmpty();
  }

  @Test
  void aProviderThatCannotBeReachedForTheRenewalGetsTheStoredToken() {
    OAuthClient.Grant grant = consent(bearer());
    server.unreachable(true);

    tokens
        .revocation(
            profile,
            new StoredTokens(grant.refreshToken(), grant.accessToken(), NOW.minusSeconds(60)))
        .run();

    assertThat(server.requests("refresh_token")).hasSize(1);
    assertThat(server.revocations())
        .singleElement()
        .satisfies(
            request ->
                assertThat(request.authorization()).isEqualTo("Bearer " + grant.accessToken()));
  }

  @Test
  void anExpiredAccessTokenWithoutRefreshTokenIsSentAsItIs() {
    OAuthClient.Grant grant = consent(bearer());

    tokens
        .revocation(profile, new StoredTokens(null, grant.accessToken(), NOW.minusSeconds(60)))
        .run();

    assertThat(server.requests("refresh_token")).isEmpty();
    assertThat(server.revocations())
        .singleElement()
        .satisfies(
            request ->
                assertThat(request.authorization()).isEqualTo("Bearer " + grant.accessToken()));
  }

  @Test
  void rfc7009RevokesTheRefreshTokenWithoutRenewing() {
    OAuthClient.Grant grant =
        consent(new Revocation.Rfc7009(new Endpoint.Fixed(server.revocationEndpoint())));

    tokens
        .revocation(
            profile,
            new StoredTokens(grant.refreshToken(), grant.accessToken(), NOW.minusSeconds(60)))
        .run();

    assertThat(server.requests("refresh_token")).isEmpty();
    assertThat(server.revocations())
        .singleElement()
        .satisfies(
            request -> assertThat(request.form()).containsEntry("token", grant.refreshToken()));
  }

  private Revocation bearer() {
    return new Revocation.BearerPost(new Endpoint.Fixed(server.bearerRevocationEndpoint()));
  }

  /** A grant the fake server issued, with {@code revocation} declared for {@link #profile}. */
  private OAuthClient.Grant consent(Revocation revocation) {
    OAuthAuth auth =
        new OAuthAuth(
            new Endpoint.Fixed(server.authorizationEndpoint()),
            new Endpoint.Fixed(server.tokenEndpoint()),
            revocation,
            "files.read offline_access",
            Map.of(),
            ClientAuthentication.CLIENT_SECRET_BASIC);
    ClientRegistration registration =
        new ClientRegistration(
            profile,
            ConnectionAuthMethod.OAUTH,
            "opaa",
            "client-geheimnis",
            null,
            null,
            auth,
            null,
            null,
            false,
            ProfileEndpoints.NONE,
            0);
    when(registrations.registrationOf(profile)).thenReturn(registration);
    OAuthClient client = new OAuthClient(TargetAddressValidator.disabled());
    String verifier = "a".repeat(43);
    URI url =
        client.authorizationUrl(
            registration,
            auth,
            "zustand",
            ConnectionAuthorizationService.challengeOf(verifier),
            REDIRECT);
    OAuthClient.Grant grant =
        client.exchange(registration, auth, server.approve(url), verifier, REDIRECT, NOW);
    server.reset();
    return grant;
  }
}
