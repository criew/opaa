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
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Against a running Keycloak session the parameters are what makes the difference: without them the
 * session is taken up silently and the old {@code auth_time} stays.
 */
@Testcontainers(disabledWithoutDocker = true)
class KeycloakRecentLoginTest {

  private static final URI REDIRECT = URI.create(KeycloakFixture.SOURCE_REDIRECT);
  private static final OAuthAuth PLAIN_SIGN_IN =
      new OAuthAuth(
          new Endpoint.FromProfile(),
          new Endpoint.FromProfile(),
          new Revocation.Rfc7009(new Endpoint.FromProfile()),
          "openid",
          Map.of(),
          ClientAuthentication.CLIENT_SECRET_BASIC);
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

  /**
   * What the two parameters are for: with a running Keycloak session an authorization without them
   * is answered with a code at once, no password asked, and the token names the first sign-in; with
   * them Keycloak shows the sign-in form despite the session, and the token names the new one.
   */
  @Test
  void aRunningSessionIsTakenUpSilentlyUnlessTheSignInIsForced() throws Exception {
    Browser browser = new Browser();
    String firstVerifier = verifier();
    HttpResponse<String> form =
        browser.get(
            client.authorizationUrl(
                registration(), PLAIN_SIGN_IN, "erste", challenge(firstVerifier), REDIRECT));
    Map<String, String> first = browser.submitCredentials(form);
    Instant firstSignIn = authTimeOf(PLAIN_SIGN_IN, first.get("code"), firstVerifier);
    // auth_time counts whole seconds: the forced sign-in below must land in a later one
    Thread.sleep(1_100);

    String silentVerifier = verifier();
    HttpResponse<String> silent =
        browser.get(
            client.authorizationUrl(
                registration(), PLAIN_SIGN_IN, "zweite", challenge(silentVerifier), REDIRECT));
    assertThat(silent.statusCode()).as("the running session answers without a form").isEqualTo(302);
    Map<String, String> silentBack = Browser.queryOfRedirect(silent);
    assertThat(authTimeOf(PLAIN_SIGN_IN, silentBack.get("code"), silentVerifier))
        .as("a session taken up is no new sign-in")
        .isEqualTo(firstSignIn);

    String forcedVerifier = verifier();
    HttpResponse<String> forcedForm =
        browser.get(
            client.authorizationUrl(
                registration(), FORCED_SIGN_IN, "dritte", challenge(forcedVerifier), REDIRECT));
    assertThat(forcedForm.statusCode())
        .as("prompt=login and max_age=0 ask for the password despite the session")
        .isEqualTo(200);
    Map<String, String> forced = browser.submitCredentials(forcedForm);
    assertThat(authTimeOf(FORCED_SIGN_IN, forced.get("code"), forcedVerifier)).isAfter(firstSignIn);
  }

  private Instant authTimeOf(OAuthAuth auth, String code, String verifier) throws Exception {
    OAuthClient.Grant grant =
        client.exchange(registration(), auth, code, verifier, REDIRECT, Instant.now());
    return jwt(grant.accessToken()).getClaimAsInstant(RecentLoginPolicy.AUTH_TIME_CLAIM);
  }

  private static String verifier() {
    return UUID.randomUUID().toString() + UUID.randomUUID();
  }

  /**
   * A browser that keeps Keycloak's session cookies between authorizations - unlike {@code
   * KeycloakFixture#signIn}, which starts without a session every time. Keycloak marks its cookies
   * Secure, so over plain http they are sent back by hand; redirects are not followed.
   */
  private static final class Browser {

    private static final Pattern LOGIN_ACTION =
        Pattern.compile("action=\"([^\"]*login-actions/authenticate[^\"]*)\"");

    private final HttpClient http =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, String> cookies = new LinkedHashMap<>();

    HttpResponse<String> get(URI url) throws Exception {
      return send(HttpRequest.newBuilder(url).GET());
    }

    /** Submits the sign-in form of {@code page}; the query of the redirect back. */
    Map<String, String> submitCredentials(HttpResponse<String> page) throws Exception {
      Matcher action = LOGIN_ACTION.matcher(page.body());
      assertThat(page.statusCode()).isEqualTo(200);
      assertThat(action.find()).as("a sign-in form").isTrue();
      String form =
          "username=anna.beispiel&password="
              + URLEncoder.encode(KeycloakFixture.USER_PASSWORD, StandardCharsets.UTF_8)
              + "&credentialId=";
      HttpResponse<String> back =
          send(
              HttpRequest.newBuilder(URI.create(action.group(1).replace("&amp;", "&")))
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)));
      assertThat(back.statusCode()).isEqualTo(302);
      return queryOfRedirect(back);
    }

    static Map<String, String> queryOfRedirect(HttpResponse<String> response) {
      String location = response.headers().firstValue("Location").orElse("");
      assertThat(location).startsWith(KeycloakFixture.SOURCE_REDIRECT);
      Map<String, String> query = new LinkedHashMap<>();
      for (String pair : URI.create(location).getRawQuery().split("&")) {
        int equals = pair.indexOf('=');
        query.put(
            pair.substring(0, equals),
            URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
      }
      return query;
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
      if (!cookies.isEmpty()) {
        request.header(
            "Cookie",
            String.join(
                "; ",
                cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
      }
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      for (String header : response.headers().allValues("Set-Cookie")) {
        String pair = header.split(";", 2)[0];
        int equals = pair.indexOf('=');
        String name = pair.substring(0, equals).trim();
        String value = pair.substring(equals + 1);
        if (value.isEmpty() || header.toLowerCase(Locale.ROOT).contains("max-age=0")) {
          cookies.remove(name);
        } else {
          cookies.put(name, value);
        }
      }
      return response;
    }
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
