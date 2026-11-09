package io.opaa.llm.web;

import io.opaa.auth.CurrentUser;
import io.opaa.llm.PersonalTranscriptionVocabularyService;
import io.opaa.llm.RealtimeTranscriptionClient;
import io.opaa.llm.RealtimeTranscriptionRole;
import io.opaa.llm.RealtimeTranscriptionRole.LiveSession;
import io.opaa.llm.RealtimeTranscriptionRole.RealtimeUnusableException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The live dictation of one person over {@code WS /api/v1/transcriptions/stream} (ADR-0070). The
 * browser speaks OPAA's own protocol whatever dialect the upstream has: the server sends {@code
 * ready} with the sample rate, the browser sends binary PCM16 frames and {@code commit}, the server
 * answers {@code delta} (the text so far), {@code done} and {@code error}. Any {@code error} ends
 * the session; the browser then sends its parallel recording to the batch endpoint.
 *
 * <p><b>Audio passes through and is never stored or logged</b>, and neither is recognised text. A
 * recording ends at the latest after {@link #HARD_LIMIT}, after {@link #IDLE_LIMIT} without audio,
 * or after more audio than {@link #HARD_LIMIT} holds; once committed, only {@link #DONE_LIMIT}
 * applies. A session therefore lasts at most {@code HARD_LIMIT + DONE_LIMIT}, below the slot's
 * {@code RealtimeTranscriptionSlots.STALE_AFTER}. The timers are this process's and end with its
 * sessions (ADR-0053).
 */
class TranscriptionStreamHandler extends BinaryWebSocketHandler {

  /** The browser's two minutes of dictation plus room for the handshake and the last frames. */
  static final Duration HARD_LIMIT = Duration.ofSeconds(130);

  static final Duration IDLE_LIMIT = Duration.ofSeconds(30);
  static final Duration DONE_LIMIT = Duration.ofSeconds(15);

  /** The largest audio frame accepted: one second of 24 kHz PCM16 with room to spare. */
  static final int MAX_FRAME_BYTES = 64 * 1024;

  /** The largest text message accepted; the browser sends only {@code commit}. */
  static final int MAX_TEXT_BYTES = 1024;

  static final String CALLER_ATTRIBUTE = TranscriptionStreamHandler.class.getName() + ".caller";

  private static final Logger log = LoggerFactory.getLogger(TranscriptionStreamHandler.class);
  private static final Duration TICK = Duration.ofSeconds(1);

  private final RealtimeTranscriptionRole realtime;
  private final PersonalTranscriptionVocabularyService vocabularies;
  private final ObjectMapper json;
  private final Clock clock;
  private final ScheduledExecutorService timers;
  private final ExecutorService connector;
  private final Map<String, Live> sessions = new ConcurrentHashMap<>();

  TranscriptionStreamHandler(
      RealtimeTranscriptionRole realtime,
      PersonalTranscriptionVocabularyService vocabularies,
      ObjectMapper json,
      Clock clock,
      ScheduledExecutorService timers,
      ExecutorService connector) {
    this.realtime = realtime;
    this.vocabularies = vocabularies;
    this.json = json;
    this.clock = clock;
    this.timers = timers;
    this.connector = connector;
  }

  /** Why a session ended with {@code error}; the browser shows no reason, it falls back. */
  enum Reason {
    UNAVAILABLE,
    BUSY,
    UPSTREAM,
    LIMIT,
    IDLE,
    PROTOCOL;

    String wire() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  /** The state of one browser session. */
  private final class Live implements RealtimeTranscriptionClient.Listener {

    private final WebSocketSession socket;
    private final Instant openedAt;
    private volatile Instant lastAudio;
    private volatile Instant committedAt;
    private long audioBytes;
    private volatile long maxAudioBytes;
    private volatile LiveSession upstream;
    private volatile boolean ended;
    private ScheduledFuture<?> tick;

    Live(WebSocketSession socket) {
      this.socket = socket;
      this.openedAt = clock.instant();
      this.lastAudio = openedAt;
    }

    @Override
    public void text(String text) {
      send(message("delta").put("text", text));
    }

    @Override
    public void done(String text) {
      send(message("done").put("text", text));
      end(CloseStatus.NORMAL);
    }

    @Override
    public void failed(String reason) {
      log.warn("Live transcription upstream failed: {}", reason);
      error(Reason.UPSTREAM);
    }

    void error(Reason reason) {
      send(message("error").put("reason", reason.wire()));
      end(CloseStatus.NORMAL);
    }

    void check() {
      LiveSession open = upstream;
      if (open != null) {
        open.upstream().ping();
      }
      Instant now = clock.instant();
      Instant committed = committedAt;
      if (committed != null) {
        if (Duration.between(committed, now).compareTo(DONE_LIMIT) >= 0) {
          error(Reason.UPSTREAM);
        }
      } else if (Duration.between(openedAt, now).compareTo(HARD_LIMIT) >= 0) {
        error(Reason.LIMIT);
      } else if (Duration.between(lastAudio, now).compareTo(IDLE_LIMIT) >= 0) {
        error(Reason.IDLE);
      }
    }

    synchronized void send(ObjectNode message) {
      if (ended || !socket.isOpen()) {
        return;
      }
      try {
        socket.sendMessage(new TextMessage(json.writeValueAsString(message)));
      } catch (IOException | IllegalStateException | SessionLimitExceededException e) {
        end(CloseStatus.SERVER_ERROR);
      }
    }

    synchronized void end(CloseStatus status) {
      if (ended) {
        return;
      }
      ended = true;
      release();
      try {
        socket.close(status);
      } catch (IOException e) {
        // The browser is gone; nothing is left to release.
      }
    }

    void release() {
      if (tick != null) {
        tick.cancel(false);
      }
      LiveSession open = upstream;
      upstream = null;
      if (open != null) {
        open.close();
      }
    }
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession socket) {
    socket.setBinaryMessageSizeLimit(MAX_FRAME_BYTES);
    socket.setTextMessageSizeLimit(MAX_TEXT_BYTES);
    WebSocketSession guarded = new ConcurrentWebSocketSessionDecorator(socket, 10_000, 512 * 1024);
    Live live = new Live(guarded);
    sessions.put(socket.getId(), live);
    live.tick =
        timers.scheduleWithFixedDelay(
            live::check, TICK.toMillis(), TICK.toMillis(), TimeUnit.MILLISECONDS);
    CurrentUser caller = (CurrentUser) socket.getAttributes().get(CALLER_ATTRIBUTE);
    connector.execute(() -> connect(live, caller));
  }

  /** Opens the upstream off the container thread and tells the browser the sample rate. */
  private void connect(Live live, CurrentUser caller) {
    try {
      String personal = vocabularies.vocabularyOf(caller.id(), caller.organizationId());
      LiveSession session = realtime.open(caller.id(), personal, live);
      synchronized (live) {
        if (live.ended) {
          session.close();
          return;
        }
        live.maxAudioBytes = HARD_LIMIT.toSeconds() * session.sampleRate() * 2L;
        live.upstream = session;
      }
      live.lastAudio = clock.instant();
      live.send(message("ready").put("sampleRate", session.sampleRate()));
    } catch (RealtimeUnusableException e) {
      live.error(e.busy() ? Reason.BUSY : Reason.UNAVAILABLE);
    } catch (RealtimeTranscriptionClient.RealtimeUnavailableException e) {
      live.error(Reason.UPSTREAM);
    } catch (RuntimeException e) {
      log.warn("Live transcription could not be opened: {}", e.getClass().getSimpleName());
      live.error(Reason.UPSTREAM);
    }
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage message) {
    Live live = sessions.get(socket.getId());
    if (live == null) {
      return;
    }
    LiveSession open = live.upstream;
    if (open == null || live.committedAt != null) {
      live.error(Reason.PROTOCOL);
      return;
    }
    byte[] frame = new byte[message.getPayloadLength()];
    message.getPayload().get(frame);
    live.audioBytes += frame.length;
    if (live.audioBytes > live.maxAudioBytes || !open.upstream().audio(frame)) {
      live.error(Reason.LIMIT);
      return;
    }
    live.lastAudio = clock.instant();
  }

  @Override
  protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
    Live live = sessions.get(socket.getId());
    if (live == null) {
      return;
    }
    String type;
    try {
      JsonNode node = json.readTree(message.getPayload());
      JsonNode value = node == null ? null : node.get("type");
      type = value == null ? "" : value.asString();
    } catch (JacksonException e) {
      type = "";
    }
    LiveSession open = live.upstream;
    if (!"commit".equals(type) || open == null || live.committedAt != null) {
      live.error(Reason.PROTOCOL);
      return;
    }
    live.committedAt = clock.instant();
    open.upstream().commit();
  }

  @Override
  public void handleTransportError(WebSocketSession socket, Throwable exception) {
    Live live = sessions.get(socket.getId());
    if (live != null) {
      live.end(CloseStatus.SERVER_ERROR);
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
    Live live = sessions.remove(socket.getId());
    if (live != null) {
      synchronized (live) {
        live.ended = true;
        live.release();
      }
    }
  }

  private ObjectNode message(String type) {
    ObjectNode message = json.createObjectNode();
    message.put("type", type);
    return message;
  }
}
