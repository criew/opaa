package io.opaa.llm;

import io.opaa.common.Feature;
import io.opaa.common.FeatureFlags;
import io.opaa.instance.ScheduledRunLocks;
import java.time.Clock;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The transcription model role, built like {@link RerankModelRole}: the one entry point for "may a
 * recording be transcribed?" and "what state is the role in?".
 *
 * <p>{@link #currentStatus()} and {@link #usable()} never probe; the state is kept current by
 * {@link #refresh()} at startup and on a schedule, and by every real call. The role is never fatal:
 * a broken endpoint hides dictation, it does not stop the application.
 *
 * <p>Switched off - by {@code OPAA_TRANSCRIPTION_ENABLED} or the feature speech-input - the role
 * contacts nobody.
 *
 * <p>The last known state is the installation's ({@link ModelRoleObservations}, ADR-0053,
 * Entscheidung 12): the instance holding the probe lock probes, every instance shows the microphone
 * on that state. The slots are this process's (ADR-0021): with N instances the installation holds N
 * times {@link TranscriptionProperties#maxConcurrent()}, so it is configured as the installation's
 * budget divided by N.
 */
@Service
public class TranscriptionModelRole implements TranscriptionRoleStatusProvider {

  private static final Logger log = LoggerFactory.getLogger(TranscriptionModelRole.class);

  private static final long PROBE_INTERVAL_MILLIS = 60_000;

  private final TranscriptionProperties properties;
  private final TranscriptionClient client;
  private final boolean switchedOn;
  private final boolean baseUrlRejected;
  private final ModelRoleObservations observations;
  private final Semaphore slots;

  /** A role whose state lives in this process only - for a context without a database. */
  public TranscriptionModelRole(
      TranscriptionProperties properties, TranscriptionClient client, FeatureFlags featureFlags) {
    this(
        properties, client, featureFlags, new ProcessLocalModelRoleObservations(Clock.systemUTC()));
  }

  @Autowired
  public TranscriptionModelRole(
      TranscriptionProperties properties,
      TranscriptionClient client,
      FeatureFlags featureFlags,
      ModelRoleObservations observations) {
    this.properties = properties;
    this.observations = observations;
    this.client = client;
    this.switchedOn = properties.enabled() && featureFlags.isEnabled(Feature.SPEECH_INPUT);
    this.baseUrlRejected = ModelEndpointUri.containsCredentials(properties.baseUrl());
    this.slots = new Semaphore(properties.maxConcurrent());
  }

  /** Signals that no recording may be passed on right now; nothing was sent. */
  public static class TranscriptionRoleUnusableException extends RuntimeException {
    public TranscriptionRoleUnusableException(String message) {
      super(message);
    }
  }

  /** Signals that every slot is taken; nothing was read or sent. */
  public static class TranscriptionRoleBusyException extends RuntimeException {
    TranscriptionRoleBusyException() {
      super("all transcription slots are taken");
    }
  }

  /** One of this instance's slots; closing it frees the slot. */
  public interface Slot extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * Takes one of {@link TranscriptionProperties#maxConcurrent()} slots without waiting. The cap
   * holds for this instance, never per person; hold the slot while the recording is in memory.
   *
   * @throws TranscriptionRoleBusyException when every slot is taken.
   */
  public Slot reserve() {
    if (!slots.tryAcquire()) {
      throw new TranscriptionRoleBusyException();
    }
    AtomicBoolean released = new AtomicBoolean();
    return () -> {
      if (released.compareAndSet(false, true)) {
        slots.release();
      }
    };
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
    return observations
        .current(ModelRoleObservations.TRANSCRIPTION)
        .filter(known -> known.isFor(properties.baseUrl(), properties.model()))
        .map(
            known ->
                known.reachable()
                    ? status(TranscriptionRoleState.READY, null, false)
                    : status(
                        TranscriptionRoleState.UNREACHABLE, known.diagnostic(), known.timedOut()))
        .orElseGet(
            () -> status(TranscriptionRoleState.UNREACHABLE, "endpoint not probed yet", false));
  }

  @Scheduled(
      fixedDelay = PROBE_INTERVAL_MILLIS,
      initialDelay = PROBE_INTERVAL_MILLIS,
      scheduler = "transcriptionProbeScheduler")
  @SchedulerLock(
      name = "transcription-role-probe",
      lockAtLeastFor = "PT50S",
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_SHORT)
  public void probePeriodically() {
    refresh();
  }

  /** Probes the endpoint and records the result. Blocking; never on a request path. */
  public TranscriptionRoleStatus refresh() {
    if (!switchedOn || baseUrlRejected || !properties.bound()) {
      return currentStatus();
    }
    TranscriptionClient.ProbeFailure failure = client.probe(properties);
    TranscriptionRoleStatus probed =
        failure == null
            ? status(TranscriptionRoleState.READY, null, false)
            : status(TranscriptionRoleState.UNREACHABLE, failure.message(), failure.timedOut());
    record(probed, true);
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
   * Transcribes one recording. A call that does not reach the endpoint moves the role to {@link
   * TranscriptionRoleState#UNREACHABLE}; an endpoint that answered but refused this one recording
   * leaves the role as it is, since the recording comes from a person, not from the server. The log
   * line names endpoint and cause, never content.
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
      record(status(TranscriptionRoleState.READY, null, false), false);
      return text;
    } catch (TranscriptionClient.TranscriptionUnavailableException e) {
      if (!e.answered()) {
        record(status(TranscriptionRoleState.UNREACHABLE, e.getMessage(), e.timedOut()), false);
      }
      // Only the message: it is technical and content-free by contract, a stack trace adds nothing.
      log.warn(
          "Transcription call against {} failed: {}",
          properties.describeWithoutSecrets(),
          e.getMessage());
      throw e;
    }
  }

  /** Records an outcome; a store that cannot take it costs the record, never the recording. */
  private void record(TranscriptionRoleStatus status, boolean probe) {
    try {
      observations.record(
          ModelRoleObservations.TRANSCRIPTION,
          new ModelRoleObservations.ModelRoleObservation(
              properties.baseUrl(),
              properties.model(),
              status.state() == TranscriptionRoleState.READY,
              status.timedOut(),
              status.diagnostic(),
              null),
          probe);
    } catch (DataAccessException e) {
      log.warn("Transcription role state could not be recorded: {}", e.getMessage());
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
