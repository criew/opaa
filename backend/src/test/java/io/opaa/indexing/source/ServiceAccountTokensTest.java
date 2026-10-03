package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.opaa.common.ValidationException;
import io.opaa.security.TargetAddressValidator;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The core's service account sign-in (ADR-0040, Entscheidung 2): the assertion the token endpoint
 * receives, the reuse of a token until shortly before it expires, the German findings for a refused
 * exchange and the absence of key, assertion and token from every log line.
 */
class ServiceAccountTokensTest {

  private static final String SCOPE = "https://www.googleapis.com/auth/drive.readonly";
  private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

  private final ServiceAccountKeyFixture key = new ServiceAccountKeyFixture();
  private final MutableClock clock = new MutableClock(NOW);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private FakeTokenEndpoint endpoint;
  private ServiceAccountTokens tokens;
  private ServiceAccountKeyAuth auth;

  @BeforeEach
  void setUp() {
    endpoint = new FakeTokenEndpoint();
    auth = new ServiceAccountKeyAuth(endpoint.uri(), SCOPE);
    tokens = new ServiceAccountTokens(TargetAddressValidator.disabled(), clock);
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    for (String name : List.of("io.opaa.indexing.source", "io.opaa.sourceaccess")) {
      Logger logger = (Logger) LoggerFactory.getLogger(name);
      logger.setLevel(Level.TRACE);
      logger.addAppender(appender);
    }
  }

  @AfterEach
  void tearDown() {
    for (String name : List.of("io.opaa.indexing.source", "io.opaa.sourceaccess")) {
      Logger logger = (Logger) LoggerFactory.getLogger(name);
      logger.detachAppender(appender);
      logger.setLevel(null);
    }
    endpoint.close();
  }

  @Test
  void theAssertionIsSignedWithTheKeyAndNamesAccountScopeAudienceAndSubject() throws Exception {
    String token = tokens.accessToken(storedKey(), "fachkonto@example.org", auth, null);

    assertThat(token).isEqualTo("ya29.test-token");
    assertThat(endpoint.forms()).hasSize(1);
    assertThat(endpoint.forms().get(0))
        .containsEntry("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
    SignedJWT assertion = SignedJWT.parse(endpoint.forms().get(0).get("assertion"));
    assertThat(assertion.verify(new RSASSAVerifier(key.publicKey()))).isTrue();
    assertThat(assertion.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
    assertThat(assertion.getHeader().getKeyID()).isEqualTo(ServiceAccountKeyFixture.PRIVATE_KEY_ID);
    var claims = assertion.getJWTClaimsSet();
    assertThat(claims.getIssuer()).isEqualTo(ServiceAccountKeyFixture.CLIENT_EMAIL);
    assertThat(claims.getAudience()).containsExactly(endpoint.uri().toString());
    assertThat(claims.getStringClaim("scope")).isEqualTo(SCOPE);
    assertThat(claims.getSubject()).isEqualTo("fachkonto@example.org");
    assertThat(claims.getIssueTime().toInstant()).isEqualTo(NOW);
    assertThat(claims.getExpirationTime().toInstant()).isEqualTo(NOW.plus(Duration.ofHours(1)));
  }

  @Test
  void withoutSubjectTheAssertionImitatesNoOne() throws Exception {
    tokens.accessToken(storedKey(), null, auth, null);

    SignedJWT assertion = SignedJWT.parse(endpoint.forms().get(0).get("assertion"));
    assertThat(assertion.getJWTClaimsSet().getSubject()).isNull();
  }

  @Test
  void theTargetIsTheTokenEndpointOfTheDescriptionNeverTheTokenUriOfTheKeyFile() {
    // the fixture's token_uri names evil.example.org; only the description's endpoint is asked
    tokens.accessToken(key.json(), null, auth, null);

    assertThat(endpoint.forms()).hasSize(1);
  }

  @Test
  void aTokenIsReusedUntilShortlyBeforeItExpiresThenRenewed() {
    endpoint.answer(
        FakeTokenEndpoint.token("erstes", 3600), FakeTokenEndpoint.token("zweites", 3600));

    assertThat(tokens.accessToken(storedKey(), null, auth, null)).isEqualTo("erstes");
    clock.advance(Duration.ofMinutes(54));
    assertThat(tokens.accessToken(storedKey(), null, auth, null)).isEqualTo("erstes");
    clock.advance(Duration.ofMinutes(1));
    assertThat(tokens.accessToken(storedKey(), null, auth, null)).isEqualTo("zweites");
    assertThat(endpoint.forms()).hasSize(2);
  }

  @Test
  void anotherSubjectGetsItsOwnToken() {
    endpoint.answer(FakeTokenEndpoint.token("a", 3600), FakeTokenEndpoint.token("b", 3600));

    assertThat(tokens.accessToken(storedKey(), "a@example.org", auth, null)).isEqualTo("a");
    assertThat(tokens.accessToken(storedKey(), "b@example.org", auth, null)).isEqualTo("b");
  }

  @Test
  void aRefusedKeyNamesTheAccountAndNotTheKey() {
    endpoint.answer(FakeTokenEndpoint.error(400, "invalid_grant", "Invalid JWT Signature."));

    assertThatThrownBy(() -> tokens.accessToken(storedKey(), null, auth, null))
        .isInstanceOf(SourceCredentialsException.class)
        .hasMessageContaining(ServiceAccountKeyFixture.CLIENT_EMAIL)
        .hasMessageContaining("ungültig, widerrufen");
  }

  @Test
  void aMissingDelegationIsNamedAsSuch() {
    endpoint.answer(
        FakeTokenEndpoint.error(
            401,
            "unauthorized_client",
            "Client is unauthorized to retrieve access tokens using this method, or client not"
                + " authorized for any of the scopes requested."));

    assertThatThrownBy(() -> tokens.accessToken(storedKey(), "fach@example.org", auth, null))
        .isInstanceOf(SourceCredentialsException.class)
        .hasMessageContaining("domänenweite Delegation fehlt")
        .hasMessageContaining("fach@example.org");
  }

  @Test
  void anUnknownImitatedAccountIsNamedAsSuch() {
    endpoint.answer(FakeTokenEndpoint.error(400, "invalid_grant", "Invalid email or User ID"));

    assertThatThrownBy(() -> tokens.accessToken(storedKey(), "weg@example.org", auth, null))
        .isInstanceOf(SourceCredentialsException.class)
        .hasMessageContaining("weg@example.org ist in der Domäne nicht bekannt");
  }

  @Test
  void anOversizedAnswerIsNamedAsSuch() {
    endpoint.answer(new FakeTokenEndpoint.Answer(200, "{\"x\":\"" + "a".repeat(70_000) + "\"}"));

    assertThatThrownBy(() -> tokens.accessToken(storedKey(), null, auth, null))
        .isInstanceOf(SourceCredentialsException.class)
        .hasMessageContaining("zu große Antwort");
  }

  @Test
  void aBlockedTokenEndpointIsNeverContacted() {
    ServiceAccountTokens guarded =
        new ServiceAccountTokens(new TargetAddressValidator(true, List.of()), clock);

    assertThatThrownBy(() -> guarded.accessToken(storedKey(), null, auth, null))
        .isInstanceOf(SourceCredentialsException.class);
    assertThat(endpoint.forms()).isEmpty();
  }

  @Test
  void anUnreachableEndpointIsAFindingNotACrash() {
    ServiceAccountKeyAuth closed =
        new ServiceAccountKeyAuth(URI.create("http://127.0.0.1:1/token"), SCOPE);

    assertThatThrownBy(() -> tokens.accessToken(storedKey(), null, closed, null))
        .isInstanceOf(SourceCredentialsException.class)
        .hasMessageContaining("nicht erreichbar");
  }

  @Test
  void aKeyFileWithoutPrivateKeyIsA400ThatDoesNotQuoteIt() {
    String broken = "{\"client_email\":\"x@example.org\",\"private_key_id\":\"geheim-4711\"}";

    assertThatThrownBy(() -> ServiceAccountKey.parse(broken))
        .isInstanceOf(ValidationException.class)
        .hasMessageNotContaining("geheim-4711");
    assertThatThrownBy(() -> ServiceAccountKey.parse("kein json " + key.privateKeyMarker()))
        .isInstanceOf(ValidationException.class)
        .hasMessageNotContaining(key.privateKeyMarker());
  }

  @Test
  void theStoredFormKeepsOnlyTheThreeFieldsTheCoreSignsWith() {
    String stored = storedKey();

    assertThat(stored)
        .contains("client_email", "private_key_id", "private_key")
        .doesNotContain("token_uri", "evil.example.org", "client_id", "project_id");
    assertThat(ServiceAccountKey.parse(stored).toString())
        .isEqualTo("ServiceAccountKey[" + ServiceAccountKeyFixture.CLIENT_EMAIL + "]");
  }

  @Test
  void noLogLineCarriesKeyAssertionOrToken() {
    endpoint.answer(
        FakeTokenEndpoint.token("ya29.geheimes-token", 3600),
        FakeTokenEndpoint.error(400, "invalid_grant", "Invalid JWT Signature."));
    tokens.accessToken(storedKey(), "fach@example.org", auth, null);
    clock.advance(Duration.ofHours(2));
    assertThatThrownBy(() -> tokens.accessToken(storedKey(), "fach@example.org", auth, null))
        .isInstanceOf(SourceCredentialsException.class);
    ServiceAccountKeyAuth closed =
        new ServiceAccountKeyAuth(URI.create("http://127.0.0.1:1/token"), SCOPE);
    assertThatThrownBy(() -> tokens.accessToken(storedKey(), null, closed, null))
        .isInstanceOf(SourceCredentialsException.class);

    String assertion = endpoint.forms().get(0).get("assertion");
    String logs =
        appender.list.stream()
            .map(
                event ->
                    event.getFormattedMessage()
                        + (event.getThrowableProxy() == null
                            ? ""
                            : event.getThrowableProxy().getMessage()))
            .collect(Collectors.joining("\n"));
    assertThat(appender.list).isNotEmpty();
    assertThat(logs)
        .doesNotContain(key.privateKeyMarker())
        .doesNotContain(assertion.substring(assertion.lastIndexOf('.') + 1))
        .doesNotContain("ya29.geheimes-token");
  }

  private String storedKey() {
    return ServiceAccountKey.parse(key.json()).storedForm();
  }

  /** A clock the test moves forward. */
  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }
}
