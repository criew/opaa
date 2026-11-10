package io.opaa.llm.transcription;

import io.opaa.common.Feature;
import io.opaa.common.FeatureFlags;
import io.opaa.instance.ScheduledRunLocks;
import io.opaa.llm.ModelRoleObservations;
import io.opaa.llm.OptionalModelRole;
import io.opaa.llm.OptionalRoleState;
import io.opaa.llm.OptionalRoleStatus;
import io.opaa.llm.ProcessLocalModelRoleObservations;
import java.time.Clock;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The transcription model role, built like {@code RerankModelRole}: the one entry point for "may a
 * recording be transcribed?" and "what state is the role in?".
 *
 * <p>Switch, address, model, key and language are the administration's ({@link
 * TranscriptionEndpoints}, ADR-0060, Entscheidung 10), read on every call; the feature speech-input
 * is the operation's limit (ADR-0060, Entscheidung 2): switched off there, the role is off whatever
 * is stored and contacts nobody.
 *
 * <p>{@link #currentStatus()} and {@link #usable()} never probe; the state is kept current by
 * {@link #refresh()} at startup and on a schedule, and by every real call. The role is never fatal:
 * a broken endpoint hides dictation, it does not stop the application.
 *
 * <p>The last known state is the installation's ({@link ModelRoleObservations}, ADR-0053,
 * Entscheidung 12): the instance holding the probe lock probes, every instance shows the microphone
 * on that state. The slots are this process's (ADR-0053): with N instances the installation holds N
 * times {@link TranscriptionProperties#maxConcurrent()}, so it is configured as the installation's
 * budget divided by N.
 */
@Service
public class TranscriptionModelRole extends OptionalModelRole<TranscriptionEndpoint>
    implements TranscriptionRoleStatusProvider {

  private static final Logger log = LoggerFactory.getLogger(TranscriptionModelRole.class);

  private static final long PROBE_INTERVAL_MILLIS = 60_000;

  private final TranscriptionProperties properties;
  private final TranscriptionClient client;
  private final boolean featureEnabled;
  private final Semaphore slots;

  /** A role whose state lives in this process only - for a context without a database. */
  public TranscriptionModelRole(
      TranscriptionProperties properties,
      Supplier<TranscriptionEndpoint> endpoints,
      TranscriptionClient client,
      FeatureFlags featureFlags) {
    this(
        properties,
        endpoints,
        client,
        featureFlags,
        new ProcessLocalModelRoleObservations(Clock.systemUTC()));
  }

  @Autowired
  public TranscriptionModelRole(
      TranscriptionProperties properties,
      TranscriptionEndpoints endpoints,
      TranscriptionClient client,
      FeatureFlags featureFlags,
      ModelRoleObservations observations) {
    this(properties, endpoints::current, client, featureFlags, observations);
  }

  public TranscriptionModelRole(
      TranscriptionProperties properties,
      Supplier<TranscriptionEndpoint> endpoints,
      TranscriptionClient client,
      FeatureFlags featureFlags,
      ModelRoleObservations observations) {
    super(ModelRoleObservations.TRANSCRIPTION, "transcription", endpoints, observations);
    this.properties = properties;
    this.client = client;
    this.featureEnabled = featureFlags.isEnabled(Feature.SPEECH_INPUT);
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

  /** Whether the operation allows speech input at all (the feature speech-input). */
  public boolean featureEnabled() {
    return featureEnabled;
  }

  @Override
  protected boolean operationAllows() {
    return featureEnabled;
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

  @Override
  protected ProbeFailure probe(TranscriptionEndpoint endpoint) {
    TranscriptionClient.ProbeFailure failure = client.probe(endpoint, properties.timeout());
    return failure == null ? null : new ProbeFailure(failure.message(), failure.timedOut());
  }

  /** The largest recording accepted, in bytes. */
  public int maxAudioBytes() {
    return (int) Math.min(properties.maxAudioBytes(), Integer.MAX_VALUE - 1);
  }

  /**
   * Transcribes one recording with the installation's vocabulary list and {@code
   * personalVocabulary} as prompt ({@link TranscriptionVocabulary#prompt}). A call that does not
   * reach the endpoint moves the role to {@link OptionalRoleState#UNREACHABLE}; an endpoint that
   * answered but refused this one recording leaves the role as it is, since the recording comes
   * from a person, not from the server. The log line names endpoint and cause, never content.
   *
   * @throws TranscriptionRoleUnusableException when the role is not {@link
   *     OptionalRoleState#READY}; nothing was sent.
   * @throws TranscriptionClient.TranscriptionUnavailableException when the endpoint failed.
   */
  public String transcribe(
      byte[] audio, TranscriptionAudioFormat format, String personalVocabulary) {
    TranscriptionEndpoint endpoint = endpoint();
    OptionalRoleStatus status = statusFor(endpoint);
    if (status.state() != OptionalRoleState.READY) {
      throw new TranscriptionRoleUnusableException("transcription role is " + status.state());
    }
    try {
      String prompt = TranscriptionVocabulary.prompt(endpoint.vocabulary(), personalVocabulary);
      String text = client.transcribe(endpoint, properties.timeout(), audio, format, prompt);
      record(endpoint, ready(endpoint), false);
      return text;
    } catch (TranscriptionClient.TranscriptionUnavailableException e) {
      if (!e.answered()) {
        record(endpoint, unreachable(endpoint, e.getMessage(), e.timedOut()), false);
      }
      // Only the message: it is technical and content-free by contract, a stack trace adds nothing.
      log.warn(
          "Transcription call against {} failed: {}",
          endpoint.describeWithoutSecrets(),
          e.getMessage());
      throw e;
    }
  }
}
