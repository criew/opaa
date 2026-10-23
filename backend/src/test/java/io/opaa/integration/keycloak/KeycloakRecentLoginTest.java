package io.opaa.integration.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.SignedJWT;
import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.auth.RecentLoginPolicy;
import io.opaa.auth.RecentLoginProperties;
import io.opaa.common.AccessDeniedException;
import io.opaa.connection.oauth.OAuthClient;
import io.opaa.connection.profile.ClientRegistration;
import io.opaa.connection.profile.ProfileEndpoints;
import io.opaa.indexing.spi.ClientAuthentication;
import io.opaa.indexing.spi.Endpoint;
import io.opaa.indexing.spi.OAuthAuth;
import io.opaa.indexing.spi.Revocation;
import io.opaa.security.TargetAddressValidator;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The recent sign-in against a real Keycloak: a sign-in forced with {@code prompt=login} and {@code
 * max_age=0} - the parameters the SPA sends for a confirmation - yields an access token whose
 * {@code auth_time} passes {@link RecentLoginPolicy}; a token renewed with the refresh token keeps
 * that {@code auth_time}, so once the window has passed the renewal does not make it recent again.
 */
@Testcontainers(disabledWithoutDocker = true)
class KeycloakRecentLoginTest {

  private static final URI REDIRECT = URI.create(KeycloakFixture.SOURCE_REDIRECT);
  private static final OAuthAuth FORCED_SIGN_IN =
      new OAuthAuth(
          new Endpoint.FromProfile(),
          new Endpoint.FromProfile(),
          new Revocation.Rfc7009(new Endpoint.FromProfile()),
          "openid",
          Map.of("prompt", "login", "max_age", "0"),
          ClientAuthentication.CLIENT_SECRET_BASIC);

  private static KeycloakFixture keycloak;

  private final OAuthClient client = new OAuthClient(TargetAddressValidator.disabled());

  @BeforeAll
  static void start() {
    keycloak = KeycloakFixture.get();
  }

  @Test
  void aForcedSignInIsRecentAndARenewalDoesNotMakeAnOldOneRecentAgain() throws Exception {
    Instant before = Instant.now().minusSeconds(5);
    String verifier = UUID.randomUUID().toString() + UUID.randomUUID();
    URI url =
        client.authorizationUrl(
            registration(), FORCED_SIGN_IN, "zustand", challenge(verifier), REDIRECT);
    assertThat(url.getRawQuery()).contains("prompt=login").contains("max_age=0");
    Map<String, String> back = keycloak.signIn(url, "anna.beispiel", KeycloakFixture.USER_PASSWORD);
    OAuthClient.Grant grant =
        client.exchange(
            registration(), FORCED_SIGN_IN, back.get("code"), verifier, REDIRECT, Instant.now());

    Jwt signedIn = jwt(grant.accessToken());
    Instant authTime = signedIn.getClaimAsInstant(RecentLoginPolicy.AUTH_TIME_CLAIM);
    assertThat(authTime).as("Keycloak names the sign-in in the access token").isNotNull();
    assertThat(authTime)
        .isAfterOrEqualTo(before.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    assertThatCode(() -> policyAt(Instant.now()).requireRecentLogin(authentication(signedIn)))
        .doesNotThrowAnyException();

    OAuthClient.Grant renewed =
        client.refresh(registration(), FORCED_SIGN_IN, grant.refreshToken(), Instant.now());
    Jwt renewedToken = jwt(renewed.accessToken());
    assertThat(renewedToken.getClaimAsInstant(RecentLoginPolicy.AUTH_TIME_CLAIM))
        .as("a renewal is no sign-in")
        .isEqualTo(authTime);

    RecentLoginPolicy sixMinutesLater = policyAt(authTime.plus(Duration.ofMinutes(6)));
    assertThatThrownBy(() -> sixMinutesLater.requireRecentLogin(authentication(renewedToken)))
        .isInstanceOfSatisfying(
            AccessDeniedException.class,
            denied -> {
              assertThat(denied.getCode()).isEqualTo(RecentLoginPolicy.CODE);
              assertThat(denied.getReason()).isEqualTo("AUTH_TIME_TOO_OLD");
            });
  }

  private static RecentLoginPolicy policyAt(Instant now) {
    return new RecentLoginPolicy(
        new RecentLoginProperties(Duration.ofMinutes(5)), Clock.fixed(now, ZoneOffset.UTC));
  }

  /** The claims as Keycloak issued them; the signature is the resource server's concern. */
  private static Jwt jwt(String token) throws Exception {
    Map<String, Object> claims = SignedJWT.parse(token).getJWTClaimsSet().toJSONObject();
    return new Jwt(
        token,
        Instant.now().minusSeconds(1),
        Instant.now().plusSeconds(300),
        Map.of("alg", "RS256"),
        claims);
  }

  private static Authentication authentication(Jwt jwt) {
    return new JwtAuthenticationToken(jwt, List.of());
  }

  private static ClientRegistration registration() {
    String realm = keycloak.issuerUri() + "/protocol/openid-connect";
    return new ClientRegistration(
        UUID.randomUUID(),
        ConnectionAuthMethod.OAUTH,
        KeycloakFixture.SOURCE_CLIENT_ID,
        KeycloakFixture.SOURCE_CLIENT_SECRET,
        null,
        null,
        FORCED_SIGN_IN,
        null,
        false,
        new ProfileEndpoints(realm + "/auth", realm + "/token", realm + "/revoke"),
        0);
  }

  private static String challenge(String verifier) throws Exception {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
  }
}
