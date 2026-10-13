package io.opaa.llm;

import io.opaa.common.Feature;
import io.opaa.common.FeatureFlags;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The transcription model role (#1926), built like {@link RerankModelRole}: the one entry point for
 * "may a recording be transcribed?" and "what state is the role in?".
 *
 * <p>{@link #currentStatus()} and {@link #usable()} never probe; the state is kept current by
 * {@link #refresh()} at startup and on a schedule, and by every real call. The role is never fatal:
 * a broken endpoint hides dictation, it does not stop the application.
 *
 * <p>Switched off - by {@code OPAA_TRANSCRIPTION_ENABLED} or the feature speech-input - the role
 * contacts nobody.
 */
@Service
public class TranscriptionModelRole implements TranscriptionRoleStatusProvider {

  private static final Logger log = LoggerFactory.getLogger(TranscriptionModelRole.class);

  private static final long PROBE_INTERVAL_MILLIS = 60_000;

  private final TranscriptionProperties properties;
  private final TranscriptionClient client;
  private final boolean switchedOn;
  private final boolean baseUrlRejected;
  private final AtomicReference<TranscriptionRoleStatus> lastKnown = new AtomicReference<>();

  public TranscriptionModelRole(
      TranscriptionProperties properties, TranscriptionClient client, FeatureFlags featureFlags) {
    this.properties = properties;
    this.client = client;
    this.switchedOn = properties.enabled() && featureFlags.isEnabled(Feature.SPEECH_INPUT);
    this.baseUrlRejected = ModelEndpointUri.containsCredentials(properties.baseUrl());
  }

  /** Signals that no recording may be passed on right now; nothing was sent. */
  public static class TranscriptionRoleUnusableException extends RuntimeException {
    TranscriptionRoleUnusableException(String message) {
      super(message);
    }
  }

  @Override
  public TranscriptionRoleStatus currentStatus() {
    if (!switchedOn) {
      return TranscriptionRoleStatus.disabled();
    }
    if (baseUrlRejected) {
      return new TranscriptionRoleStatus(
          TranscriptionRoleState.UNCONFIGURED,
          null,
          emptyToNull(properties.model()),
          "opaa.transcription.base-url carries credentials and was rejected",
          false,
          true);
    }
    if (!properties.bound()) {
      return new TranscriptionRoleStatus(
          TranscriptionRoleState.UNCONFIGURED,
          emptyToNull(properties.baseUrl()),
          emptyToNull(properties.model()),
          "opaa.transcription.base-url and/or opaa.transcription.model are unset",
          false,
          false);
    }
    TranscriptionRoleStatus known = lastKnown.get();
    return known != null
        ? known
        : status(TranscriptionRoleState.UNREACHABLE, "endpoint not probed yet", false);
  }

  @Scheduled(
      fixedDelay = PROBE_INTERVAL_MILLIS,
      initialDelay = PROBE_INTERVAL_MILLIS,
      scheduler = "transcriptionProbeScheduler")
  void probePeriodically() {
    refresh();
  }

  /** Probes the endpoint and records the result. Blocking; never on a request path. */
  public TranscriptionRoleStatus refresh() {
    if (!switchedOn || baseUrlRejected || !properties.bound()) {
      lastKnown.set(null);
      return currentStatus();
    }
    TranscriptionClient.ProbeFailure failure = client.probe(properties);
    TranscriptionRoleStatus probed =
        failure == null
            ? status(TranscriptionRoleState.READY, null, false)
            : status(TranscriptionRoleState.UNREACHABLE, failure.message(), failure.timedOut());
    lastKnown.set(probed);
    return probed;
  }

  /** Whether a recording may be passed on right now. Never blocks. */
  public boolean usable() {
    return currentStatus().state() == TranscriptionRoleState.READY;
  }

  /** The largest recording accepted, in bytes. */
  public int maxAudioBytes() {
    return (int) Math.min(properties.maxAudioBytes(), Integer.MAX_VALUE - 1);
  }

  /**
   * Transcribes one recording. A failed call moves the role to {@link
   * TranscriptionRoleState#UNREACHABLE}; the log line names endpoint and cause, never content.
   *
   * @throws TranscriptionRoleUnusableException when the role is not {@link
   *     TranscriptionRoleState#READY}; nothing was sent.
   * @throws TranscriptionClient.TranscriptionUnavailableException when the endpoint failed.
   */
  public String transcribe(byte[] audio, TranscriptionAudioFormat format) {
    if (!usable()) {
      throw new TranscriptionRoleUnusableException(
          "transcription role is " + currentStatus().state());
    }
    try {
      String text = client.transcribe(properties, audio, format);
      lastKnown.set(status(TranscriptionRoleState.READY, null, false));
      return text;
    } catch (TranscriptionClient.TranscriptionUnavailableException e) {
      lastKnown.set(status(TranscriptionRoleState.UNREACHABLE, e.getMessage(), e.timedOut()));
      // Only the message: it is technical and content-free by contract, a stack trace adds nothing.
      log.warn(
          "Transcription call against {} failed: {}",
          properties.describeWithoutSecrets(),
          e.getMessage());
      throw e;
    }
  }

  private TranscriptionRoleStatus status(
      TranscriptionRoleState state, String diagnostic, boolean timedOut) {
    return new TranscriptionRoleStatus(
        state, properties.baseUrl(), properties.model(), diagnostic, timedOut, false);
  }

  private static String emptyToNull(String value) {
    return value.isEmpty() ? null : value;
  }
}
