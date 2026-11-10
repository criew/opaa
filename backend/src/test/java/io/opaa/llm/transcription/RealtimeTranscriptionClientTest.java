package io.opaa.llm.transcription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.types.RealtimeTranscriptionProtocol;
import io.opaa.llm.transcription.RealtimeTranscriptionClient.RealtimeUnavailableException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Each dialect against a local fake upstream: what goes out in which order, how the answers become
 * the text so far and the final text, and that a broken upstream ends the session with a reason
 * that carries neither audio nor text.
 */
class RealtimeTranscriptionClientTest {

  private static final byte[] PCM = {1, 2, 3, 4, 5, 6};
  private static final String SECRET_TEXT = "Aktenzeichen 12-345 der Frau Muster";

  private final ObjectMapper json = new ObjectMapper();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final RealtimeTranscriptionClient client =
      new RealtimeTranscriptionClient(
          List.of(
              new OpenAiRealtimeTranscriptionAdapter(json),
              new VllmRealtimeTranscriptionAdapter(json),
              new MistralRealtimeTranscriptionAdapter(json)),
          json,
          registry);
  private FakeRealtimeUpstream upstream;
  private final Recorder recorder = new Recorder();

  @BeforeEach
  void startUpstream() throws Exception {
    upstream = new FakeRealtimeUpstream();
  }

  @AfterEach
  void stop() throws Exception {
    upstream.close();
    client.close();
  }

  @Test
  void openAiTranscribesCommittedItemsInOrderAndEndsOnceAllAreCompleted() throws Exception {
    upstream.onOpen(connection -> connection.send("{\"type\":\"session.created\"}"));
    upstream.onMessage(
        (connection, message) -> {
          if (message.contains("input_audio_buffer.commit")) {
            connection.send("{\"type\":\"input_audio_buffer.committed\",\"item_id\":\"a\"}");
            connection.send(
                "{\"type\":\"conversation.item.input_audio_transcription.delta\","
                    + "\"item_id\":\"a\",\"delta\":\"Guten \"}");
            connection.send(
                "{\"type\":\"conversation.item.input_audio_transcription.delta\","
                    + "\"item_id\":\"a\",\"delta\":\"Tag\"}");
            connection.send(
                "{\"type\":\"conversation.item.input_audio_transcription.completed\","
                    + "\"item_id\":\"a\",\"transcript\":\"Guten Tag.\"}");
          }
        });

    RealtimeTranscriptionClient.Session session =
        client.open(
            endpoint(RealtimeTranscriptionProtocol.OPENAI, "/v1/realtime", "gpt-live-transcribe"),
            "de",
            "Referat Z 4",
            recorder);
    FakeRealtimeUpstream.Connection connection = upstream.connection(0);

    assertThat(connection.target()).isEqualTo("/v1/realtime?intent=transcription");
    assertThat(connection.header("Authorization")).isEqualTo("Bearer sk-live");
    JsonNode update = json.readTree(connection.next());
    assertThat(update.at("/type").asString()).isEqualTo("session.update");
    assertThat(update.at("/session/type").asString()).isEqualTo("transcription");
    assertThat(update.at("/session/audio/input/format/type").asString()).isEqualTo("audio/pcm");
    assertThat(update.at("/session/audio/input/format/rate").asInt()).isEqualTo(24_000);
    assertThat(update.at("/session/audio/input/transcription/model").asString())
        .isEqualTo("gpt-live-transcribe");
    assertThat(update.at("/session/audio/input/transcription/languages/0").asString())
        .isEqualTo("de");
    assertThat(update.at("/session/audio/input/transcription/prompt").asString())
        .isEqualTo("Referat Z 4");
    assertThat(update.at("/session/audio/input/turn_detection").isNull()).isTrue();

    session.audio(PCM);
    JsonNode append = json.readTree(connection.next());
    assertThat(append.at("/type").asString()).isEqualTo("input_audio_buffer.append");
    assertThat(Base64.getDecoder().decode(append.at("/audio").asString())).isEqualTo(PCM);

    session.commit();
    assertThat(json.readTree(connection.next()).at("/type").asString())
        .isEqualTo("input_audio_buffer.commit");
    assertThat(recorder.next()).isEqualTo("text:Guten");
    assertThat(recorder.next()).isEqualTo("text:Guten Tag");
    assertThat(recorder.next()).isEqualTo("done:Guten Tag.");
    assertThat(client.sampleRate(RealtimeTranscriptionProtocol.OPENAI)).isEqualTo(24_000);
  }

  @Test
  void openAiPassesTheLanguageAsASingleValueToModelsOtherThanGptLiveTranscribe() throws Exception {
    client.open(
        endpoint(RealtimeTranscriptionProtocol.OPENAI, "/v1/realtime", "gpt-4o-transcribe"),
        "de",
        "",
        recorder);

    JsonNode transcription =
        json.readTree(upstream.connection(0).next()).at("/session/audio/input/transcription");
    assertThat(transcription.at("/language").asString()).isEqualTo("de");
    assertThat(transcription.has("languages")).isFalse();
  }

  @Test
  void openAiEndsWithTheCompletedTextEvenIfDeltasBeforeTheCommitCarriedAnotherItemId()
      throws Exception {
    upstream.onMessage(
        (connection, message) -> {
          if (message.contains("input_audio_buffer.append")) {
            connection.send(
                "{\"type\":\"conversation.item.input_audio_transcription.delta\","
                    + "\"item_id\":\"draft\",\"delta\":\"Guten Ta\"}");
          }
          if (message.contains("input_audio_buffer.commit")) {
            connection.send("{\"type\":\"input_audio_buffer.committed\",\"item_id\":\"a\"}");
            connection.send(
                "{\"type\":\"conversation.item.input_audio_transcription.completed\","
                    + "\"item_id\":\"a\",\"transcript\":\"Guten Tag.\"}");
          }
        });
    RealtimeTranscriptionClient.Session session =
        client.open(
            endpoint(RealtimeTranscriptionProtocol.OPENAI, "/v1/realtime", "gpt-live-transcribe"),
            "",
            "",
            recorder);

    session.audio(PCM);
    assertThat(recorder.next()).isEqualTo("text:Guten Ta");
    session.commit();

    assertThat(recorder.next()).isEqualTo("done:Guten Tag.");
  }

  @Test
  void anUpstreamMessageAboveTheLimitEndsTheSession() throws Exception {
    String huge = "x".repeat(RealtimeTranscriptionClient.MAX_MESSAGE_CHARS);
    upstream.onOpen(
        connection ->
            connection.send("{\"type\":\"transcription.delta\",\"delta\":\"" + huge + "\"}"));

    client.open(
        endpoint(RealtimeTranscriptionProtocol.VLLM, "/v1/realtime", "v"), "", "", recorder);

    assertThat(recorder.next()).startsWith("failed:").contains("larger than");
  }

  @Test
  void textGatheredAboveTheLimitEndsTheSession() throws Exception {
    String chunk = "x".repeat(RealtimeTranscriptionAdapter.MAX_TEXT_CHARS / 2 + 1);
    upstream.onOpen(
        connection -> {
          connection.send("{\"type\":\"transcription.delta\",\"delta\":\"" + chunk + "\"}");
          connection.send("{\"type\":\"transcription.delta\",\"delta\":\"" + chunk + "\"}");
        });

    client.open(
        endpoint(RealtimeTranscriptionProtocol.VLLM, "/v1/realtime", "v"), "", "", recorder);

    assertThat(recorder.next()).startsWith("text:");
    assertThat(recorder.next()).startsWith("failed:").contains("more text than");
  }

  @Test
  void audioIsRefusedOnceTooManyFramesWaitForAnUpstreamThatDoesNotRead() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    upstream.onMessage(
        (connection, message) -> {
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    RealtimeTranscriptionClient.Session session =
        client.open(
            endpoint(RealtimeTranscriptionProtocol.VLLM, "/v1/realtime", "v"), "", "", recorder);
    byte[] frame = new byte[64 * 1024];

    boolean refused = false;
    for (int sent = 0; sent < 2_000 && !refused; sent++) {
      refused = !session.audio(frame);
    }
    release.countDown();

    assertThat(refused).isTrue();
  }

  @Test
  void aRedirectedHandshakeIsNotFollowedSoTheKeyGoesNowhereElse() throws Exception {
    try (FakeRealtimeUpstream elsewhere = new FakeRealtimeUpstream()) {
      upstream.redirectTo(elsewhere.url("/v1/realtime"));

      assertThatThrownBy(
              () ->
                  client.open(
                      endpoint(RealtimeTranscriptionProtocol.OPENAI, "/v1/realtime", "m"),
                      "",
                      "",
                      recorder))
          .isInstanceOfSatisfying(
              RealtimeUnavailableException.class, e -> assertThat(e.getMessage()).contains("307"));
      assertThat(upstream.handshakeAuthorizations()).containsExactly("Bearer sk-live");
      assertThat(elsewhere.handshakeAuthorizations()).isEmpty();
    }
  }

  @Test
  void vllmStartsTheBufferRightAwayAndEndsWithTheFinalCommit() throws Exception {
    upstream.onMessage(
        (connection, message) -> {
          if (message.contains("\"final\":true")) {
            connection.send("{\"type\":\"transcription.delta\",\"delta\":\"Guten \"}");
            connection.send("{\"type\":\"transcription.delta\",\"delta\":\"Tag\"}");
            connection.send("{\"type\":\"transcription.done\",\"text\":\" Guten Tag. \"}");
          }
        });

    RealtimeTranscriptionClient.Session session =
        client.open(
            endpoint(
                RealtimeTranscriptionProtocol.VLLM,
                "/v1/realtime",
                "mistralai/Voxtral-Mini-4B-Realtime-2602"),
            "de",
            "",
            recorder);
    FakeRealtimeUpstream.Connection connection = upstream.connection(0);

    assertThat(connection.target()).isEqualTo("/v1/realtime");
    JsonNode update = json.readTree(connection.next());
    assertThat(update.at("/type").asString()).isEqualTo("session.update");
    assertThat(update.at("/model").asString()).isEqualTo("mistralai/Voxtral-Mini-4B-Realtime-2602");
    assertThat(connection.next()).isEqualTo("{\"type\":\"input_audio_buffer.commit\"}");

    session.audio(PCM);
    JsonNode append = json.readTree(connection.next());
    assertThat(append.at("/type").asString()).isEqualTo("input_audio_buffer.append");
    assertThat(Base64.getDecoder().decode(append.at("/audio").asString())).isEqualTo(PCM);

    session.commit();
    assertThat(connection.next())
        .isEqualTo("{\"type\":\"input_audio_buffer.commit\",\"final\":true}");
    assertThat(recorder.next()).isEqualTo("text:Guten");
    assertThat(recorder.next()).isEqualTo("text:Guten Tag");
    assertThat(recorder.next()).isEqualTo("done:Guten Tag.");
    assertThat(client.sampleRate(RealtimeTranscriptionProtocol.VLLM)).isEqualTo(16_000);
  }

  @Test
  void mistralNamesTheModelInTheAddressAndPassesTheVocabularyAsContextBias() throws Exception {
    upstream.onMessage(
        (connection, message) -> {
          if (message.contains("input_audio.end")) {
            connection.send("{\"type\":\"transcription.text.delta\",\"text\":\"Guten \"}");
            connection.send("{\"type\":\"transcription.text.delta\",\"text\":\"Tag\"}");
            connection.send("{\"type\":\"transcription.done\",\"text\":\"Guten Tag.\"}");
          }
        });

    RealtimeTranscriptionClient.Session session =
        client.open(
            endpoint(
                RealtimeTranscriptionProtocol.MISTRAL,
                "/v1/audio/transcriptions/realtime",
                "voxtral-mini-transcribe-realtime-3"),
            "de",
            "Referat Z 4; Az. 12-345\nOPAA",
            recorder);
    FakeRealtimeUpstream.Connection connection = upstream.connection(0);

    assertThat(connection.target())
        .isEqualTo("/v1/audio/transcriptions/realtime?model=voxtral-mini-transcribe-realtime-3");
    JsonNode update = json.readTree(connection.next());
    assertThat(update.at("/session/audio_format/encoding").asString()).isEqualTo("pcm_s16le");
    assertThat(update.at("/session/audio_format/sample_rate").asInt()).isEqualTo(16_000);
    assertThat(update.at("/session/language").asString()).isEqualTo("de");
    assertThat(update.at("/session/context_bias").valueStream().map(JsonNode::asString).toList())
        .containsExactly("Referat Z 4", "Az. 12-345", "OPAA");

    session.audio(PCM);
    assertThat(json.readTree(connection.next()).at("/type").asString())
        .isEqualTo("input_audio.append");
    session.commit();
    assertThat(connection.next()).isEqualTo("{\"type\":\"input_audio.end\"}");
    assertThat(recorder.next()).isEqualTo("text:Guten");
    assertThat(recorder.next()).isEqualTo("text:Guten Tag");
    assertThat(recorder.next()).isEqualTo("done:Guten Tag.");
  }

  @Test
  void anUpstreamThatDropsTheConnectionMidRecordingEndsTheSessionWithoutText() throws Exception {
    upstream.onMessage(
        (connection, message) -> {
          if (message.contains("input_audio_buffer.append")) {
            connection.send("{\"type\":\"transcription.delta\",\"delta\":\"" + SECRET_TEXT + "\"}");
            connection.drop();
          }
        });
    RealtimeTranscriptionClient.Session session =
        client.open(
            endpoint(RealtimeTranscriptionProtocol.VLLM, "/v1/realtime", "v"), "", "", recorder);

    session.audio(PCM);
    assertThat(recorder.next()).isEqualTo("text:" + SECRET_TEXT);
    String failure = null;
    for (int frame = 0; frame < 50 && failure == null; frame++) {
      session.audio(PCM);
      failure = recorder.poll();
    }

    assertThat(failure).isNotNull();
    assertThat(failure).startsWith("failed:").doesNotContain(SECRET_TEXT);
    session.commit();
    assertThat(recorder.poll()).isNull();
  }

  @Test
  void aPingAfterTheCommitNoticesAnUpstreamThatDroppedTheConnection() throws Exception {
    upstream.onMessage(
        (connection, message) -> {
          if (message.contains("\"final\":true")) {
            connection.drop();
          }
        });
    RealtimeTranscriptionClient.Session session =
        client.open(
            endpoint(RealtimeTranscriptionProtocol.VLLM, "/v1/realtime", "v"), "", "", recorder);
    session.audio(PCM);
    session.commit();
    upstream.connection(0);

    String failure = null;
    for (int tick = 0; tick < 50 && failure == null; tick++) {
      session.ping();
      failure = recorder.poll();
    }

    assertThat(failure).startsWith("failed:");
  }

  @Test
  void anErrorEventEndsTheSessionWithItsCodeButNeverItsMessage() throws Exception {
    upstream.onMessage(
        (connection, message) ->
            connection.send(
                "{\"type\":\"error\",\"error\":{\"code\":\"invalid_api_key\",\"message\":\""
                    + SECRET_TEXT
                    + "\"}}"));

    client.open(endpoint(RealtimeTranscriptionProtocol.MISTRAL, "/v1/x", "m"), "", "", recorder);

    String failure = recorder.next();
    assertThat(failure)
        .startsWith("failed:")
        .contains("invalid_api_key")
        .doesNotContain(SECRET_TEXT);
  }

  @Test
  void aRefusedHandshakeIsAnAnsweredFailureWithItsStatus() {
    upstream.refuseWith(401);

    assertThatThrownBy(
            () ->
                client.open(
                    endpoint(RealtimeTranscriptionProtocol.OPENAI, "/v1/realtime", "m"),
                    "",
                    "",
                    recorder))
        .isInstanceOfSatisfying(
            RealtimeUnavailableException.class,
            e -> {
              assertThat(e.answered()).isTrue();
              assertThat(e.timedOut()).isFalse();
              assertThat(e.getMessage()).contains("401");
            });
    assertThat(handshakes("auth_error", "interactive")).isEqualTo(1);
  }

  @Test
  void anUnreachableUpstreamIsAFailureThatWasNotAnswered() throws Exception {
    String url = upstream.url("/v1/realtime");
    upstream.close();

    assertThatThrownBy(
            () ->
                client.open(
                    new RealtimeTranscriptionEndpoint(
                        RealtimeTranscriptionProtocol.VLLM, url, "m", "", 1),
                    "",
                    "",
                    recorder))
        .isInstanceOfSatisfying(
            RealtimeUnavailableException.class, e -> assertThat(e.answered()).isFalse());
    assertThat(handshakes("io_error", "interactive")).isEqualTo(1);
  }

  @Test
  void aHandshakeThatGetsNoAnswerInTimeCountsAsATimeout() throws Exception {
    try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      RealtimeTranscriptionClient.ProbeFailure failure =
          client.probe(
              new RealtimeTranscriptionEndpoint(
                  RealtimeTranscriptionProtocol.VLLM,
                  "ws://127.0.0.1:" + silent.getLocalPort() + "/v1/realtime",
                  "m",
                  "",
                  1),
              Duration.ofMillis(300));

      assertThat(failure).isNotNull();
      assertThat(failure.timedOut()).isTrue();
    }
    assertThat(handshakes("timeout", "background")).isEqualTo(1);
  }

  @Test
  void aProbeSucceedsWhenTheUpstreamAnswersAndSendsNoAudio() throws Exception {
    upstream.onOpen(connection -> connection.send("{\"type\":\"session.created\"}"));

    RealtimeTranscriptionClient.ProbeFailure failure =
        client.probe(
            endpoint(RealtimeTranscriptionProtocol.OPENAI, "/v1/realtime", "m"),
            Duration.ofSeconds(5));

    assertThat(failure).isNull();
    assertThat(upstream.connection(0).messages())
        .noneMatch(message -> message.contains("input_audio_buffer.append"));
    assertThat(handshakes("success", "background")).isEqualTo(1);
  }

  @Test
  void aProbeFailsWhenTheUpstreamObjectsToTheSession() {
    upstream.onMessage(
        (connection, message) ->
            connection.send("{\"type\":\"error\",\"error\":{\"code\":\"model_not_found\"}}"));

    RealtimeTranscriptionClient.ProbeFailure failure =
        client.probe(
            endpoint(RealtimeTranscriptionProtocol.OPENAI, "/v1/realtime", "unknown"),
            Duration.ofSeconds(5));

    assertThat(failure).isNotNull();
    assertThat(failure.message()).contains("model_not_found");
  }

  @Test
  void aProbeOfASilentUpstreamFails() {
    RealtimeTranscriptionClient.ProbeFailure failure =
        client.probe(
            endpoint(RealtimeTranscriptionProtocol.MISTRAL, "/v1/x", "m"), Duration.ofSeconds(5));

    assertThat(failure).isNotNull();
    assertThat(failure.timedOut()).isTrue();
  }

  private long handshakes(String outcome, String pathClass) {
    Timer timer =
        registry
            .find("opaa.outbound.requests")
            .tags("target", "transcription", "outcome", outcome, "path_class", pathClass)
            .timer();
    return timer == null ? 0 : timer.count();
  }

  private RealtimeTranscriptionEndpoint endpoint(
      RealtimeTranscriptionProtocol protocol, String path, String model) {
    return new RealtimeTranscriptionEndpoint(protocol, upstream.url(path), model, "sk-live", 1);
  }

  /** Records the listener calls in order. */
  private static final class Recorder implements RealtimeTranscriptionClient.Listener {

    private final BlockingQueue<String> calls = new LinkedBlockingQueue<>();

    @Override
    public void text(String text) {
      calls.add("text:" + text);
    }

    @Override
    public void done(String text) {
      calls.add("done:" + text);
    }

    @Override
    public void failed(String reason) {
      calls.add("failed:" + reason);
    }

    String next() throws InterruptedException {
      String call = calls.poll(5, TimeUnit.SECONDS);
      if (call == null) {
        throw new AssertionError("the listener was not called");
      }
      return call;
    }

    String poll() throws InterruptedException {
      return calls.poll(300, TimeUnit.MILLISECONDS);
    }
  }
}
