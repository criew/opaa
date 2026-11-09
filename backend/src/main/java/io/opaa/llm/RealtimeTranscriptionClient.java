package io.opaa.llm;

import io.opaa.api.types.RealtimeTranscriptionProtocol;
import io.opaa.compliance.DataFlow;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Opens the WebSocket of a live transcription upstream and runs one recording through the adapter
 * of its dialect (ADR-0070). Uses the JDK client like {@link TranscriptionClient}: the
 * installation-wide outbound proxy and the JVM truststore apply, and a redirect of the handshake is
 * never followed, so the key cannot be replayed elsewhere. The key goes as {@code Authorization:
 * Bearer} in the handshake only.
 *
 * <p><b>Nothing but the audio, the language and the vocabulary leaves this class.</b> No message of
 * an exception, a {@link Listener#failed} reason or a log line carries audio, recognised text or a
 * message body of the upstream.
 */
@DataFlow("transcription-realtime")
@Component
public class RealtimeTranscriptionClient {

  /** How long the handshake of a session may take. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /** How long a probe waits for the upstream to object to the session it was offered. */
  static final Duration PROBE_GRACE = Duration.ofMillis(1500);

  /** The largest message accepted from the upstream, like {@link TranscriptionClient}'s answer. */
  static final int MAX_MESSAGE_CHARS = TranscriptionClient.MAX_RESPONSE_BYTES;

  /** How many audio frames may wait for the upstream before the session is refused more. */
  static final int MAX_PENDING_FRAMES = 32;

  private final Map<RealtimeTranscriptionProtocol, RealtimeTranscriptionAdapter> adapters =
      new EnumMap<>(RealtimeTranscriptionProtocol.class);
  private final ObjectMapper json;
  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .connectTimeout(CONNECT_TIMEOUT)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public RealtimeTranscriptionClient(
      List<RealtimeTranscriptionAdapter> adapters, ObjectMapper json) {
    adapters.forEach(adapter -> this.adapters.put(adapter.protocol(), adapter));
    this.json = json;
  }

  /** Aborts the open sessions: a live session would otherwise hold the shutdown for minutes. */
  @PreDestroy
  void close() {
    httpClient.shutdownNow();
  }

  /** The upstream could not be used; the message is technical English without content. */
  public static class RealtimeUnavailableException extends RuntimeException {

    private final boolean timedOut;
    private final boolean answered;

    RealtimeUnavailableException(String message, boolean timedOut, boolean answered) {
      super(message);
      this.timedOut = timedOut;
      this.answered = answered;
    }

    public boolean timedOut() {
      return timedOut;
    }

    /** Whether the upstream answered the handshake at all - refused, but reachable. */
    public boolean answered() {
      return answered;
    }
  }

  /** A probe's failure; {@code null} from {@link #probe} means the upstream took the session. */
  public record ProbeFailure(String message, boolean timedOut) {}

  /** What a running session reports; called on the client's threads, one call at a time. */
  public interface Listener {

    /** The text recognised so far, as a whole. */
    void text(String text);

    /** The final text; the session is over. */
    void done(String text);

    /** The upstream failed or closed before {@link #done}; the session is over. */
    void failed(String reason);
  }

  /** One recording on its way to the upstream. */
  public interface Session {

    /**
     * Passes one frame of PCM16 audio in {@link #sampleRate(RealtimeTranscriptionProtocol)}; {@code
     * false} when {@link #MAX_PENDING_FRAMES} frames still wait for the upstream and this one was
     * dropped, so the caller ends the session instead of buffering without bound.
     */
    boolean audio(byte[] pcm16);

    /** Ends the recording; the upstream then answers with its final text. */
    void commit();

    /**
     * Sends a ping. The JDK client does not notice an upstream that drops the connection without
     * closing it while nothing is sent; a failing ping ends the session with {@link
     * Listener#failed}.
     */
    void ping();

    /** Closes the connection; no listener call follows. */
    void close();
  }

  /** The sample rate the dialect expects. */
  public int sampleRate(RealtimeTranscriptionProtocol protocol) {
    return adapter(protocol).sampleRate();
  }

  /**
   * Opens a session and sends its opening; blocks until the handshake is done.
   *
   * @throws RealtimeUnavailableException when the upstream cannot be reached or refuses
   */
  public Session open(
      RealtimeTranscriptionEndpoint endpoint,
      String language,
      String vocabulary,
      Listener listener) {
    RealtimeTranscriptionAdapter adapter = adapter(endpoint.protocol());
    RealtimeTranscriptionAdapter.Dialog dialog =
        adapter.open(
            endpoint, language == null ? "" : language, vocabulary == null ? "" : vocabulary);
    Connection connection = new Connection(dialog, listener);
    connection.connect(adapter, endpoint);
    dialog.opening().forEach(connection::send);
    return connection;
  }

  /**
   * Opens a session, offers it to the upstream without audio and closes it again; {@code null} when
   * the upstream took it. Blocking.
   */
  public ProbeFailure probe(RealtimeTranscriptionEndpoint endpoint, Duration timeout) {
    CompletableFuture<String> answer = new CompletableFuture<>();
    Listener listener =
        new Listener() {
          @Override
          public void text(String text) {}

          @Override
          public void done(String text) {}

          @Override
          public void failed(String reason) {
            answer.complete(reason);
          }
        };
    Connection connection;
    try {
      RealtimeTranscriptionAdapter adapter = adapter(endpoint.protocol());
      connection = new Connection(adapter.open(endpoint, "", ""), listener);
      connection.probing = true;
      connection.connect(adapter, endpoint, timeout);
      connection.dialog.opening().forEach(connection::send);
    } catch (RealtimeUnavailableException e) {
      return new ProbeFailure(e.getMessage(), e.timedOut());
    }
    try {
      String reason = answer.get(PROBE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
      return new ProbeFailure(reason, false);
    } catch (TimeoutException e) {
      return connection.answered.get()
          ? null
          : new ProbeFailure("the upstream did not answer the session", true);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ProbeFailure("the probe was interrupted", false);
    } catch (ExecutionException e) {
      return new ProbeFailure("the probe failed", false);
    } finally {
      connection.close();
    }
  }

  private RealtimeTranscriptionAdapter adapter(RealtimeTranscriptionProtocol protocol) {
    RealtimeTranscriptionAdapter adapter = protocol == null ? null : adapters.get(protocol);
    if (adapter == null) {
      throw new RealtimeUnavailableException("no adapter for protocol " + protocol, false, false);
    }
    return adapter;
  }

  /** One WebSocket to the upstream; sends are chained, since the JDK allows one at a time. */
  private final class Connection implements Session, WebSocket.Listener {

    private final RealtimeTranscriptionAdapter.Dialog dialog;
    private final Listener listener;
    private final StringBuilder partial = new StringBuilder();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean answered = new AtomicBoolean();
    private final AtomicInteger pendingFrames = new AtomicInteger();
    private volatile boolean probing;
    private WebSocket socket;
    private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);

    Connection(RealtimeTranscriptionAdapter.Dialog dialog, Listener listener) {
      this.dialog = dialog;
      this.listener = listener;
    }

    void connect(RealtimeTranscriptionAdapter adapter, RealtimeTranscriptionEndpoint endpoint) {
      connect(adapter, endpoint, CONNECT_TIMEOUT);
    }

    void connect(
        RealtimeTranscriptionAdapter adapter,
        RealtimeTranscriptionEndpoint endpoint,
        Duration timeout) {
      URI uri;
      try {
        uri = adapter.uri(endpoint);
      } catch (IllegalArgumentException e) {
        throw new RealtimeUnavailableException(e.getMessage(), false, false);
      }
      WebSocket.Builder builder = httpClient.newWebSocketBuilder().connectTimeout(timeout);
      if (StringUtils.hasText(endpoint.apiKey())) {
        builder.header("Authorization", "Bearer " + endpoint.apiKey());
      }
      try {
        socket = builder.buildAsync(uri, this).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        throw new RealtimeUnavailableException("connection attempt timed out", true, false);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RealtimeUnavailableException("the connection was interrupted", false, false);
      } catch (ExecutionException | IllegalArgumentException e) {
        throw describe(e instanceof ExecutionException ? e.getCause() : e);
      }
    }

    synchronized void send(String message) {
      WebSocket target = socket;
      chain(() -> target.sendText(message, true));
    }

    private synchronized void chain(Supplier<CompletableFuture<?>> next) {
      sending =
          sending
              .thenCompose(ignored -> next.get())
              .exceptionally(
                  failure -> {
                    fail("sending to the upstream failed");
                    return null;
                  });
    }

    @Override
    public void ping() {
      WebSocket target = socket;
      if (!finished.get() && target != null) {
        chain(() -> target.sendPing(ByteBuffer.allocate(0)));
      }
    }

    @Override
    public boolean audio(byte[] pcm16) {
      if (finished.get()) {
        return true;
      }
      if (pendingFrames.incrementAndGet() > MAX_PENDING_FRAMES) {
        pendingFrames.decrementAndGet();
        return false;
      }
      String message = dialog.audio(pcm16);
      synchronized (this) {
        WebSocket target = socket;
        chain(
            () ->
                target
                    .sendText(message, true)
                    .whenComplete((sent, failure) -> pendingFrames.decrementAndGet()));
      }
      return true;
    }

    @Override
    public void commit() {
      if (!finished.get()) {
        dialog.commit().forEach(this::send);
      }
    }

    @Override
    public void close() {
      finished.set(true);
      WebSocket target = socket;
      if (target != null) {
        target.abort();
      }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      if (partial.length() + data.length() > MAX_MESSAGE_CHARS) {
        partial.setLength(0);
        failWith(webSocket, "the upstream sent a message larger than " + MAX_MESSAGE_CHARS);
        return null;
      }
      partial.append(data);
      if (last) {
        String message = partial.toString();
        partial.setLength(0);
        handle(webSocket, message);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      failWith(webSocket, "the upstream closed the connection (" + statusCode + ")");
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      failWith(
          webSocket,
          error instanceof IOException io
              ? ModelRoleHttp.describeConnectionError(io, "connection attempt timed out")
              : "the connection to the upstream failed");
    }

    private void handle(WebSocket webSocket, String message) {
      JsonNode node;
      try {
        node = json.readTree(message);
      } catch (JacksonException e) {
        fail("the upstream sent no valid JSON");
        return;
      }
      RealtimeTranscriptionAdapter.Event event = dialog.receive(node);
      switch (event) {
        case RealtimeTranscriptionAdapter.Event.Text text -> {
          answered.set(true);
          if (!finished.get()) {
            listener.text(text.text());
          }
        }
        case RealtimeTranscriptionAdapter.Event.Done done -> {
          answered.set(true);
          if (finished.compareAndSet(false, true)) {
            listener.done(done.text());
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "");
          }
        }
        case RealtimeTranscriptionAdapter.Event.Failed failed ->
            failWith(webSocket, failed.reason());
        case RealtimeTranscriptionAdapter.Event.Other other -> answered.set(true);
      }
    }

    private void fail(String reason) {
      failWith(socket, reason);
    }

    private void failWith(WebSocket target, String reason) {
      if (finished.compareAndSet(false, true) || probing) {
        listener.failed(reason);
        if (target != null) {
          target.abort();
        }
      }
    }
  }

  private static RealtimeUnavailableException describe(Throwable cause) {
    Throwable failure =
        cause instanceof CompletionException && cause.getCause() != null ? cause.getCause() : cause;
    if (failure instanceof WebSocketHandshakeException handshake) {
      int status = handshake.getResponse().statusCode();
      return new RealtimeUnavailableException(
          ModelRoleHttp.describeStatus(status, "live transcription", Map.of()), false, true);
    }
    if (failure instanceof IOException io) {
      return new RealtimeUnavailableException(
          ModelRoleHttp.describeConnectionError(io, "connection attempt timed out"),
          ModelRoleHttp.isRequestTimeout(io),
          false);
    }
    if (failure instanceof IllegalArgumentException) {
      return new RealtimeUnavailableException(
          "the live address must start with ws:// or wss://", false, false);
    }
    return new RealtimeUnavailableException("the upstream could not be reached", false, false);
  }
}
