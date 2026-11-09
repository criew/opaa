package io.opaa.llm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.llm.PersonalTranscriptionVocabularyService;
import io.opaa.llm.RealtimeTranscriptionClient;
import io.opaa.llm.RealtimeTranscriptionRole;
import io.opaa.llm.RealtimeTranscriptionRole.LiveSession;
import io.opaa.llm.RealtimeTranscriptionRole.RealtimeUnusableException;
import io.opaa.test.MutableClock;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import tools.jackson.databind.ObjectMapper;

/**
 * OPAA's own live protocol in front of a stubbed upstream: {@code ready} with the sample rate,
 * audio and commit passed through, {@code delta} and {@code done} passed back, every failure and
 * every limit an {@code error} that ends the session and frees its slot.
 */
class TranscriptionStreamHandlerTest {

  private static final CurrentUser CALLER =
      CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.USER, "Erika");

  private final ObjectMapper json = new ObjectMapper();
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));
  private final RealtimeTranscriptionRole role = mock(RealtimeTranscriptionRole.class);
  private final PersonalTranscriptionVocabularyService vocabularies =
      mock(PersonalTranscriptionVocabularyService.class);
  private final ScheduledExecutorService timers = mock(ScheduledExecutorService.class);
  private final RealtimeTranscriptionClient.Session upstream =
      mock(RealtimeTranscriptionClient.Session.class);
  private final AtomicBoolean slotFreed = new AtomicBoolean();
  private final AtomicReference<RealtimeTranscriptionClient.Listener> listener =
      new AtomicReference<>();
  private final List<String> sent = new ArrayList<>();
  private final AtomicReference<CloseStatus> closed = new AtomicReference<>();
  private Runnable tick;
  private TranscriptionStreamHandler handler;
  private WebSocketSession socket;

  @BeforeEach
  void setUp() throws IOException {
    when(vocabularies.vocabularyOf(CALLER.id(), CALLER.organizationId())).thenReturn("Referat Z 4");
    when(timers.scheduleWithFixedDelay(any(), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
        .thenAnswer(
            call -> {
              tick = call.getArgument(0);
              return mock(ScheduledFuture.class);
            });
    handler =
        new TranscriptionStreamHandler(
            role, vocabularies, json, clock, timers, new DirectExecutorService());
    socket = mock(WebSocketSession.class);
    Map<String, Object> attributes = new HashMap<>();
    attributes.put(TranscriptionStreamHandler.CALLER_ATTRIBUTE, CALLER);
    when(socket.getId()).thenReturn("s1");
    when(socket.getAttributes()).thenReturn(attributes);
    when(socket.isOpen()).thenAnswer(call -> closed.get() == null);
    doAnswer(
            call -> {
              WebSocketMessage<?> message = call.getArgument(0);
              sent.add(((TextMessage) message).getPayload());
              return null;
            })
        .when(socket)
        .sendMessage(any());
    doAnswer(
            call -> {
              closed.compareAndSet(null, call.getArgument(0));
              return null;
            })
        .when(socket)
        .close(any(CloseStatus.class));
  }

  private void liveIsReady() {
    when(upstream.audio(any())).thenReturn(true);
    when(role.open(eq(CALLER.id()), eq("Referat Z 4"), any()))
        .thenAnswer(
            call -> {
              listener.set(call.getArgument(2));
              return new LiveSession(upstream, () -> slotFreed.set(true), 24_000);
            });
  }

  @Test
  void aRecordingIsPassedThroughAndItsTextComesBackUntilDone() throws Exception {
    liveIsReady();
    handler.afterConnectionEstablished(socket);
    assertThat(sent).containsExactly("{\"type\":\"ready\",\"sampleRate\":24000}");
    verify(socket).setBinaryMessageSizeLimit(TranscriptionStreamHandler.MAX_FRAME_BYTES);
    verify(socket).setTextMessageSizeLimit(TranscriptionStreamHandler.MAX_TEXT_BYTES);

    handler.handleBinaryMessage(socket, new BinaryMessage(new byte[] {1, 2, 3, 4}));
    verify(upstream).audio(new byte[] {1, 2, 3, 4});
    listener.get().text("Guten");
    handler.handleTextMessage(socket, new TextMessage("{\"type\":\"commit\"}"));
    verify(upstream).commit();
    listener.get().done("Guten Tag.");

    assertThat(sent)
        .containsExactly(
            "{\"type\":\"ready\",\"sampleRate\":24000}",
            "{\"type\":\"delta\",\"text\":\"Guten\"}",
            "{\"type\":\"done\",\"text\":\"Guten Tag.\"}");
    assertThat(closed.get()).isEqualTo(CloseStatus.NORMAL);
    verify(upstream).close();
    assertThat(slotFreed).isTrue();
  }

  @Test
  void anUpstreamThatFailsMidRecordingEndsTheSessionWithAnErrorTheBrowserFallsBackOn()
      throws Exception {
    liveIsReady();
    handler.afterConnectionEstablished(socket);
    handler.handleBinaryMessage(socket, new BinaryMessage(new byte[] {1, 2}));

    listener.get().failed("the upstream closed the connection (1006)");

    assertThat(sent).last().isEqualTo("{\"type\":\"error\",\"reason\":\"upstream\"}");
    assertThat(closed.get()).isNotNull();
    assertThat(slotFreed).isTrue();
  }

  @Test
  void aBusyInstallationIsRefusedWithBusy() throws Exception {
    when(role.open(any(), anyString(), any()))
        .thenThrow(new RealtimeUnusableException("no live session free", true));

    handler.afterConnectionEstablished(socket);

    assertThat(sent).containsExactly("{\"type\":\"error\",\"reason\":\"busy\"}");
    assertThat(closed.get()).isNotNull();
  }

  @Test
  void liveSwitchedOffIsRefusedWithUnavailable() throws Exception {
    when(role.open(any(), anyString(), any()))
        .thenThrow(new RealtimeUnusableException("live transcription is not available", false));

    handler.afterConnectionEstablished(socket);

    assertThat(sent).containsExactly("{\"type\":\"error\",\"reason\":\"unavailable\"}");
  }

  @Test
  void aSessionWithoutAudioEndsAfterTheIdleLimit() throws Exception {
    liveIsReady();
    handler.afterConnectionEstablished(socket);

    clock.advance(TranscriptionStreamHandler.IDLE_LIMIT.minusSeconds(1));
    tick.run();
    assertThat(closed.get()).isNull();
    verify(upstream).ping();

    clock.advance(Duration.ofSeconds(1));
    tick.run();
    assertThat(sent).last().isEqualTo("{\"type\":\"error\",\"reason\":\"idle\"}");
    assertThat(slotFreed).isTrue();
  }

  @Test
  void aSessionEndsAtTheHardLimitEvenWhileAudioFlows() throws Exception {
    liveIsReady();
    handler.afterConnectionEstablished(socket);

    for (Duration elapsed = Duration.ZERO;
        elapsed.compareTo(TranscriptionStreamHandler.HARD_LIMIT) < 0;
        elapsed = elapsed.plusSeconds(10)) {
      handler.handleBinaryMessage(socket, new BinaryMessage(new byte[] {1, 2}));
      clock.advance(Duration.ofSeconds(10));
      tick.run();
    }

    assertThat(sent).last().isEqualTo("{\"type\":\"error\",\"reason\":\"limit\"}");
  }

  @Test
  void aCommitWithoutFinalTextEndsAfterTheDoneLimit() throws Exception {
    liveIsReady();
    handler.afterConnectionEstablished(socket);
    handler.handleTextMessage(socket, new TextMessage("{\"type\":\"commit\"}"));

    clock.advance(TranscriptionStreamHandler.DONE_LIMIT);
    tick.run();

    assertThat(sent).last().isEqualTo("{\"type\":\"error\",\"reason\":\"upstream\"}");
  }

  @Test
  void aCommitJustBeforeTheHardLimitStillGetsItsFinalText() throws Exception {
    liveIsReady();
    handler.afterConnectionEstablished(socket);
    for (int second = 0; second < 119; second += 10) {
      handler.handleBinaryMessage(socket, new BinaryMessage(new byte[] {1, 2}));
      clock.advance(Duration.ofSeconds(10));
      tick.run();
    }
    handler.handleTextMessage(socket, new TextMessage("{\"type\":\"commit\"}"));

    clock.advance(TranscriptionStreamHandler.DONE_LIMIT.minusSeconds(1));
    tick.run();
    assertThat(closed.get()).isNull();
    listener.get().done("Guten Tag.");

    assertThat(sent).last().isEqualTo("{\"type\":\"done\",\"text\":\"Guten Tag.\"}");
  }

  @Test
  void moreAudioThanTheHardLimitHoldsEndsTheSessionWithLimit() throws Exception {
    liveIsReady();
    handler.afterConnectionEstablished(socket);
    byte[] second = new byte[24_000 * 2];

    for (long seconds = 0; seconds < TranscriptionStreamHandler.HARD_LIMIT.toSeconds(); seconds++) {
      handler.handleBinaryMessage(socket, new BinaryMessage(second));
    }
    assertThat(closed.get()).isNull();
    handler.handleBinaryMessage(socket, new BinaryMessage(new byte[] {1, 2}));

    assertThat(sent).last().isEqualTo("{\"type\":\"error\",\"reason\":\"limit\"}");
    assertThat(slotFreed).isTrue();
  }

  @Test
  void anUpstreamThatFallsBehindEndsTheSessionWithLimit() throws Exception {
    liveIsReady();
    when(upstream.audio(any())).thenReturn(false);
    handler.afterConnectionEstablished(socket);

    handler.handleBinaryMessage(socket, new BinaryMessage(new byte[] {1, 2}));

    assertThat(sent).last().isEqualTo("{\"type\":\"error\",\"reason\":\"limit\"}");
    assertThat(slotFreed).isTrue();
  }

  @Test
  void aBrowserThatReadsTooSlowlyLosesTheSessionAndItsSlot() throws Exception {
    liveIsReady();
    handler.afterConnectionEstablished(socket);
    doThrow(new SessionLimitExceededException("buffer full", CloseStatus.SESSION_NOT_RELIABLE))
        .when(socket)
        .sendMessage(any());

    listener.get().text("Guten");

    assertThat(closed.get()).isEqualTo(CloseStatus.SERVER_ERROR);
    assertThat(slotFreed).isTrue();
  }

  @Test
  void anythingButAudioAndOneCommitIsAProtocolError() throws Exception {
    liveIsReady();
    handler.afterConnectionEstablished(socket);

    handler.handleTextMessage(socket, new TextMessage("{\"type\":\"start\"}"));

    assertThat(sent).last().isEqualTo("{\"type\":\"error\",\"reason\":\"protocol\"}");
    verify(upstream, never()).commit();
  }

  @Test
  void aBrowserThatGoesAwayFreesTheSlot() throws Exception {
    liveIsReady();
    handler.afterConnectionEstablished(socket);

    handler.afterConnectionClosed(socket, CloseStatus.GOING_AWAY);

    verify(upstream).close();
    assertThat(slotFreed).isTrue();
    listener.get().text("late");
    assertThat(sent).hasSize(1);
  }

  /** Runs every task on the calling thread. */
  private static final class DirectExecutorService extends AbstractExecutorService {

    @Override
    public void execute(Runnable command) {
      command.run();
    }

    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }
}
