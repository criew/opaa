package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.test.OpaaIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The live dictation's WebSocket against the running application and Postgres: the handshake is
 * authenticated like a REST call and bound to the allowed origins, and the installation-wide cap of
 * live sessions holds across the table every instance shares.
 */
@OpaaIntegrationTest
class TranscriptionStreamIntegrationTest {

  private static final String ALLOWED_ORIGIN = "http://localhost:5173";
  private static final String PROTOCOL = "opaa-transcription.v1";

  @LocalServerPort private int port;
  @Autowired private RealtimeTranscriptionSlots slots;
  @Autowired private JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM transcription_realtime_slots");
    http.shutdownNow();
  }

  @Test
  void aSignedInPersonIsAnsweredInOpaasProtocolEvenWhileLiveIsOff() throws Exception {
    CompletableFuture<String> first = new CompletableFuture<>();
    WebSocket socket =
        open("opaa-dev-user.dev-user", ALLOWED_ORIGIN, first).get(10, TimeUnit.SECONDS);

    assertThat(socket.getSubprotocol()).isEqualTo(PROTOCOL);
    assertThat(first.get(10, TimeUnit.SECONDS))
        .isEqualTo("{\"type\":\"error\",\"reason\":\"unavailable\"}");
  }

  @Test
  void anUnknownPersonIsRefusedAtTheHandshake() {
    assertThatThrownBy(
            () ->
                open("opaa-dev-user.nobody", ALLOWED_ORIGIN, new CompletableFuture<>())
                    .get(10, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .isInstanceOfSatisfying(
            WebSocketHandshakeException.class,
            e -> assertThat(e.getResponse().statusCode()).isEqualTo(401));
  }

  @Test
  void aForeignOriginIsRefusedAtTheHandshake() {
    assertThatThrownBy(
            () ->
                open("opaa-dev-user.dev-user", "https://evil.example", new CompletableFuture<>())
                    .get(10, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .isInstanceOfSatisfying(
            WebSocketHandshakeException.class,
            e -> assertThat(e.getResponse().statusCode()).isEqualTo(403));
  }

  @Test
  void theCapCountsTheSessionsOfTheWholeInstallationAndOnePerPerson() {
    UUID erika = UUID.randomUUID();
    UUID otto = UUID.randomUUID();

    RealtimeTranscriptionSlots.Opening first = slots.open(erika, 1);
    assertThat(first.slot()).isNotNull();
    assertThat(slots.open(erika, 5).refusal())
        .isEqualTo(RealtimeTranscriptionSlots.Refusal.PERSON_BUSY);
    assertThat(slots.open(otto, 1).refusal())
        .isEqualTo(RealtimeTranscriptionSlots.Refusal.INSTALLATION_BUSY);
    assertThat(slots.hasFree(1)).isFalse();

    first.slot().close();
    assertThat(slots.hasFree(1)).isTrue();
    RealtimeTranscriptionSlots.Opening second = slots.open(otto, 1);
    assertThat(second.slot()).isNotNull();
    second.slot().close();
  }

  @Test
  void aSlotLeftBehindByAStoppedInstanceStopsCountingAfterTheHardLimit() {
    jdbc.update(
        "INSERT INTO transcription_realtime_slots (id, opened_at)"
            + " VALUES (?, clock_timestamp() - interval '10 minutes')",
        UUID.randomUUID());

    assertThat(slots.hasFree(1)).isTrue();
    RealtimeTranscriptionSlots.Opening opening = slots.open(UUID.randomUUID(), 1);
    assertThat(opening.slot()).isNotNull();
    opening.slot().close();
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM transcription_realtime_slots", Integer.class))
        .isZero();
  }

  private CompletableFuture<WebSocket> open(
      String credentials, String origin, CompletableFuture<String> first) {
    return http.newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .subprotocols(PROTOCOL, credentials)
        .header("Origin", origin)
        .buildAsync(
            URI.create("ws://localhost:" + port + "/api/v1/transcriptions/stream"),
            new WebSocket.Listener() {
              @Override
              public CompletionStage<?> onText(
                  WebSocket webSocket, CharSequence data, boolean last) {
                first.complete(data.toString());
                webSocket.request(1);
                return null;
              }
            });
  }
}
