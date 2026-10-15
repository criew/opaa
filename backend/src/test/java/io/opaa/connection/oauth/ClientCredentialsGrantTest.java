package io.opaa.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.connection.profile.ClientRegistration;
import io.opaa.connection.profile.ProfileEndpoints;
import io.opaa.indexing.credentials.SignInRejectedException;
import io.opaa.indexing.spi.ClientAuthentication;
import io.opaa.indexing.spi.ClientCredentialsAuth;
import io.opaa.indexing.spi.Endpoint;
import io.opaa.indexing.spi.SourceCredentialsException;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.FakeAuthorizationServer;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

/**
 * A refused client credentials grant: an Entra error code - from {@code error_codes} or, without
 * them, from the {@code AADSTS} prefix of the description - turns into a concrete German hint; the
 * OAuth {@code error} alone decides the exception, and the description never reaches the message.
 */
class ClientCredentialsGrantTest {

  private static final Instant NOW = Instant.parse("2026-10-15T08:00:00Z");
  private static final String SECRET = "client-geheimnis";
  private static final String PROVIDER_TEXT = "provider prose that must not surface";
  private static final String SCOPE = "https://graph.microsoft.com/.default";

  private final FakeAuthorizationServer server = new FakeAuthorizationServer();
  private final ClientCredentialsGrant grant =
      new ClientCredentialsGrant(new OAuthClient(TargetAddressValidator.disabled()));

  @AfterEach
  void stop() {
    server.close();
  }

  @ParameterizedTest(name = "AADSTS{0} as {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "7000215 | invalid_client      | true  | Geheime ID",
        "7000222 | invalid_client      | true  | abgelaufen",
        "700016  | unauthorized_client | false | Objekt-ID",
        "90002   | invalid_request     | false | Verzeichnis-ID (Mandanten-ID)",
        "900023  | invalid_request     | false | contoso.onmicrosoft.com",
        "7000216 | invalid_client      | true  | fehlt das Client-Secret",
        "900144  | invalid_request     | false | Pflichtparameter",
        "7000112 | unauthorized_client | false | deaktiviert",
        "70011   | invalid_scope       | false | gegen Microsoft Graph",
        "500011  | invalid_resource    | false | Ressource des Scopes",
      })
  void anEntraCodeNamesItsHintAndTheErrorDecidesTheException(
      int code, String error, boolean rejected, String hint) {
    server.rejectWithBody(400, entraBody(error, "[" + code + "]", code));

    assertThatThrownBy(this::token)
        .isInstanceOf(SourceCredentialsException.class)
        .satisfies(
            e -> assertThat(e instanceof SignInRejectedException).isEqualTo(rejected),
            e ->
                assertThat(e.getMessage())
                    .contains(server.tokenEndpoint().toString(), hint)
                    .doesNotContain(PROVIDER_TEXT, "AADSTS", SECRET));
  }

  @Test
  void withoutErrorCodesTheCodeIsReadFromTheDescriptionPrefix() {
    server.rejectWithBody(401, entraBody("invalid_client", null, 7000215));

    assertThatThrownBy(this::token)
        .isInstanceOf(SignInRejectedException.class)
        .hasMessageContaining("Geheime ID")
        .hasMessageNotContaining(PROVIDER_TEXT);
  }

  @Test
  void anUnknownEntraCodeFallsBackToTheHintOfTheError() {
    server.rejectWithBody(401, entraBody("invalid_client", "[700027]", 700027));

    assertThatThrownBy(this::token)
        .isInstanceOf(SignInRejectedException.class)
        .hasMessageContaining(server.tokenEndpoint().toString())
        .hasMessageContaining("Secret-ID statt Secret-Wert")
        .hasMessageNotContaining(PROVIDER_TEXT);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "invalid_client      | true  | Secret-ID statt Secret-Wert",
        "unauthorized_client | false | Anmeldeweg (Grant-Typ)",
        "invalid_scope       | false | Scope „https://graph.microsoft.com/.default“",
        "server_error        | false | (HTTP 400)",
      })
  void withoutAnEntraCodeTheErrorNamesTheCause(String error, boolean rejected, String hint) {
    server.rejectWith(400, error);

    assertThatThrownBy(this::token)
        .isInstanceOf(SourceCredentialsException.class)
        .satisfies(
            e -> assertThat(e instanceof SignInRejectedException).isEqualTo(rejected),
            e ->
                assertThat(e.getMessage())
                    .contains(server.tokenEndpoint().toString(), hint)
                    .doesNotContain(SECRET));
  }

  @Test
  void theLogNamesTheEntraCodeAndTheTraceButNoSecret() {
    server.rejectWithBody(401, entraBody("invalid_client", "[7000215]", 7000215));
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Logger logger = (Logger) LoggerFactory.getLogger(ClientCredentialsGrant.class);
    logger.addAppender(appender);
    try {
      assertThatThrownBy(this::token).isInstanceOf(SignInRejectedException.class);
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .asString()
        .contains(
            "invalid_client",
            "AADSTS7000215",
            "0f3a3c3e-1b2d-4c5e-8f90-a1b2c3d4e5f6",
            "6e5d4c3b-2a19-4f08-9e7d-c6b5a4938271")
        .doesNotContain(SECRET, PROVIDER_TEXT);
  }

  private void token() {
    grant.token(
        registration(),
        new ClientCredentialsAuth(
            new Endpoint.Fixed(server.tokenEndpoint()),
            SCOPE,
            ClientAuthentication.CLIENT_SECRET_POST),
        NOW);
  }

  /** An answer as Entra gives it; {@code codes} is the JSON array, {@code null} for none. */
  static String entraBody(String error, String codes, int code) {
    return "{\"error\": \""
        + error
        + "\", \"error_description\": \"AADSTS"
        + code
        + ": "
        + PROVIDER_TEXT
        + ". Trace ID: 0f3a3c3e\""
        + (codes == null ? "" : ", \"error_codes\": " + codes)
        + ", \"trace_id\": \"0f3a3c3e-1b2d-4c5e-8f90-a1b2c3d4e5f6\""
        + ", \"correlation_id\": \"6e5d4c3b-2a19-4f08-9e7d-c6b5a4938271\"}";
  }

  private static ClientRegistration registration() {
    return new ClientRegistration(
        UUID.randomUUID(),
        ConnectionAuthMethod.CLIENT_CREDENTIALS,
        "opaa",
        SECRET,
        null,
        null,
        null,
        null,
        false,
        ProfileEndpoints.NONE,
        0);
  }
}
