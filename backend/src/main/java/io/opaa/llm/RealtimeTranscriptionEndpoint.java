package io.opaa.llm;

import io.opaa.api.types.RealtimeTranscriptionProtocol;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import java.net.URI;
import java.util.Locale;

/**
 * The live upstream of the transcription role as the administration stored it (ADR-0070): dialect,
 * WebSocket address, model, the key to send and how many live sessions the installation holds at
 * once. Unconfigured while protocol, address or model is missing; then dictation works by recording
 * only. {@code apiKey} is the key sent - the live upstream's own key, or the batch endpoint's when
 * none of its own is stored. {@link #toString()} never names the key.
 */
public record RealtimeTranscriptionEndpoint(
    RealtimeTranscriptionProtocol protocol,
    String url,
    String model,
    String apiKey,
    int maxSessions) {

  /** The number of live sessions of the factory default. */
  public static final int DEFAULT_MAX_SESSIONS = 1;

  /** The largest number of live sessions the administration may store. */
  public static final int MAX_MAX_SESSIONS = 100;

  /** No live upstream. */
  public static final RealtimeTranscriptionEndpoint NONE =
      new RealtimeTranscriptionEndpoint(null, "", "", "", DEFAULT_MAX_SESSIONS);

  public RealtimeTranscriptionEndpoint {
    url = url == null ? "" : url.strip();
    model = model == null ? "" : model.strip();
    apiKey = apiKey == null ? "" : apiKey.strip();
    maxSessions = maxSessions < 1 ? DEFAULT_MAX_SESSIONS : maxSessions;
  }

  /** Whether dialect, address and model are present. */
  public boolean configured() {
    return protocol != null && !url.isEmpty() && !model.isEmpty();
  }

  /** The same endpoint with another key to send. */
  public RealtimeTranscriptionEndpoint withApiKey(String key) {
    return new RealtimeTranscriptionEndpoint(protocol, url, model, key, maxSessions);
  }

  /** Whether {@code url} starts with {@code ws://} or {@code wss://}. */
  public static boolean isWebSocketAddress(String url) {
    String lower = url == null ? "" : url.strip().toLowerCase(Locale.ROOT);
    return lower.startsWith("ws://") || lower.startsWith("wss://");
  }

  /**
   * {@code url} with {@code ws} as {@code http} and {@code wss} as {@code https}: the form the
   * address check of models and the comparison of stored endpoints understand. Other addresses pass
   * unchanged.
   */
  public static String httpEquivalent(String url) {
    if (url == null) {
      return null;
    }
    String text = url.strip();
    String lower = text.toLowerCase(Locale.ROOT);
    if (lower.startsWith("wss://")) {
      return "https://" + text.substring(6);
    }
    if (lower.startsWith("ws://")) {
      return "http://" + text.substring(5);
    }
    return text;
  }

  /**
   * Whether the batch key may go to the live address: only to the origin (scheme, host, port) of
   * the batch address it was stored for, so a key never follows a changed live address elsewhere.
   */
  public static boolean sharesOrigin(String liveUrl, String batchBaseUrl) {
    if (liveUrl == null || batchBaseUrl == null) {
      return false;
    }
    try {
      return RedirectFollowingFetcher.sameOrigin(
          URI.create(httpEquivalent(liveUrl)), URI.create(batchBaseUrl.strip()));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** Why a live upstream without a usable key is refused, as the field message says it. */
  public static final String KEY_RULE =
      "OpenAI und Mistral verlangen einen Zugangsschlüssel: Bitte einen Live-Schlüssel eintragen;"
          + " der Schlüssel des Transkriptionsmodells gilt nur bei gleicher Adresse bis zum Port"
          + " (Schema, Host, Port).";

  /**
   * Whether a live upstream would go without a key it needs: OPENAI and MISTRAL always ask for one,
   * and the batch key reaches the live address only on its origin ({@link #sharesOrigin}).
   */
  public static boolean lacksRequiredKey(
      RealtimeTranscriptionProtocol protocol,
      String liveUrl,
      boolean ownKey,
      String batchBaseUrl,
      boolean batchKey) {
    if (protocol != RealtimeTranscriptionProtocol.OPENAI
        && protocol != RealtimeTranscriptionProtocol.MISTRAL) {
      return false;
    }
    return !ownKey && !(batchKey && sharesOrigin(liveUrl, batchBaseUrl));
  }

  /** Dialect, model and address as far as they may be logged: never the key or query. */
  public String describeWithoutSecrets() {
    if (!configured()) {
      return "(no live upstream configured)";
    }
    return protocol + " " + model + " @ " + ModelEndpointUri.withoutSecrets(url);
  }

  @Override
  public String toString() {
    return "RealtimeTranscriptionEndpoint[protocol="
        + protocol
        + ", url="
        + ModelEndpointUri.withoutSecrets(url)
        + ", model="
        + model
        + ", apiKey=***, maxSessions="
        + maxSessions
        + "]";
  }
}
