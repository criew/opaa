package io.opaa.integration.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.connection.oauth.OAuthClient;
import io.opaa.connection.oauth.ResponseIssuer;
import io.opaa.connection.profile.ClientRegistration;
import io.opaa.connection.profile.ProfileEndpoints;
import io.opaa.indexing.source.ClientAuthentication;
import io.opaa.indexing.source.Endpoint;
import io.opaa.indexing.source.OAuthAuth;
import io.opaa.indexing.source.Revocation;
import io.opaa.indexing.source.SignInRejectedException;
import io.opaa.indexing.source.SourceCredentialsException;
import io.opaa.security.TargetAddressValidator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OAuth client against a real Keycloak (#2168): a person's consent by the authorization code
 * flow with PKCE, an offline grant, rotation of the refresh token, refusal of a used or revoked
 * one, and a code without its verifier refused - what the fake authorization server asserts,
 * re-verified against the answers a real provider gives. The endpoints come from the profile, as
 * for any Keycloak. Keycloak announces and names {@code iss} (RFC 9207); a sign-in declaring its
 * issuer accepts that response, one declaring another refuses it.
 */
@Testcontainers(disabledWithoutDocker = true)
class KeycloakOAuthConsentTest {

  private static final URI REDIRECT = URI.create(KeycloakFixture.SOURCE_REDIRECT);
  private static final OAuthAuth FROM_PROFILE =
      new OAuthAuth(
          new Endpoint.FromProfile(),
          new Endpoint.FromProfile(),
          new Revocation.Rfc7009(new Endpoint.FromProfile()),
          "openid offline_access",
          Map.of(),
          ClientAuthentication.CLIENT_SECRET_BASIC);

  private static KeycloakFixture keycloak;

  private final OAuthClient client = new OAuthClient(TargetAddressValidator.disabled());

  @BeforeAll
  static void start() {
    keycloak = KeycloakFixture.get();
  }

  @Test
  void aConsentWithPkceGivesAnOfflineGrantWhoseRefreshTokenRotates() throws Exception {
    OAuthClient.Grant grant = consent();

    assertThat(grant.refreshToken()).isNotBlank();
    assertThat(grant.accessTokenExpiresAt()).isAfter(Instant.now());

    OAuthClient.Grant renewed =
        client.refresh(registration(), FROM_PROFILE, grant.refreshToken(), Instant.now());

    assertThat(renewed.accessToken()).isNotEqualTo(grant.accessToken());
    assertThat(renewed.refreshToken()).isNotBlank().isNotEqualTo(grant.refreshToken());
    assertThatThrownBy(
            () -> client.refresh(registration(), FROM_PROFILE, grant.refreshToken(), Instant.now()))
        .as("a used refresh token is refused once it rotated")
        .isInstanceOf(SignInRejectedException.class);
  }

  @Test
  void aRevokedGrantIsNoLongerRenewed() throws Exception {
    OAuthClient.Grant grant = consent();

    assertThat(client.revoke(registration(), FROM_PROFILE, grant.refreshToken(), null)).isTrue();

    assertThatThrownBy(
            () -> client.refresh(registration(), FROM_PROFILE, grant.refreshToken(), Instant.now()))
        .isInstanceOf(SignInRejectedException.class);
  }

  @Test
  void keycloakNamesTheIssuerItAnnouncesAndOnlyThatIssuerIsAccepted() throws Exception {
    HttpResponse<String> discovery =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(keycloak.issuerUri() + "/.well-known/openid-configuration"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    JsonNode metadata = JsonMapper.builder().build().readTree(discovery.body());
    assertThat(metadata.get("issuer").asString()).isEqualTo(keycloak.issuerUri());
    assertThat(metadata.get("authorization_response_iss_parameter_supported").asBoolean()).isTrue();

    URI url =
        client.authorizationUrl(
            registration(), FROM_PROFILE, "zustand", challenge(verifier()), REDIRECT);
    String iss = signIn(url).get("iss");

    assertThat(iss).isEqualTo(keycloak.issuerUri());
    assertThat(ResponseIssuer.of(declaring(keycloak.issuerUri())).accepts(iss)).isTrue();
    assertThat(ResponseIssuer.of(declaring(keycloak.issuerUri())).accepts(null)).isFalse();
    assertThat(ResponseIssuer.of(declaring(keycloak.baseUrl() + "/realms/anderes")).accepts(iss))
        .isFalse();
  }

  @Test
  void aCodeWithoutItsVerifierIsRefused() throws Exception {
    String verifier = verifier();
    URI url =
        client.authorizationUrl(
            registration(), FROM_PROFILE, "zustand", challenge(verifier), REDIRECT);
    String code = signIn(url).get("code");

    assertThatThrownBy(
            () ->
                client.exchange(
                    registration(), FROM_PROFILE, code, verifier(), REDIRECT, Instant.now()))
        .isInstanceOf(SourceCredentialsException.class)
        .hasMessageNotContaining(code);
  }

  private OAuthClient.Grant consent() throws Exception {
    String verifier = verifier();
    URI url =
        client.authorizationUrl(
            registration(), FROM_PROFILE, "zustand", challenge(verifier), REDIRECT);
    Map<String, String> back = signIn(url);
    assertThat(back).containsEntry("state", "zustand");
    return client.exchange(
        registration(), FROM_PROFILE, back.get("code"), verifier, REDIRECT, Instant.now());
  }

  /** {@link #FROM_PROFILE} declaring {@code issuer} with Keycloak's announcement. */
  private static OAuthAuth declaring(String issuer) {
    return new OAuthAuth(
        FROM_PROFILE.authorization(),
        FROM_PROFILE.token(),
        FROM_PROFILE.revocation(),
        FROM_PROFILE.defaultScopes(),
        FROM_PROFILE.authorizationParams(),
        FROM_PROFILE.clientAuth(),
        issuer,
        true);
  }

  private static Map<String, String> signIn(URI url) {
    return keycloak.signIn(url, "anna.beispiel", KeycloakFixture.USER_PASSWORD);
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
        FROM_PROFILE,
        null,
        null,
        false,
        new ProfileEndpoints(realm + "/auth", realm + "/token", realm + "/revoke"),
        0);
  }

  private static String verifier() {
    return UUID.randomUUID().toString() + UUID.randomUUID();
  }

  private static String challenge(String verifier) throws Exception {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
  }
}
