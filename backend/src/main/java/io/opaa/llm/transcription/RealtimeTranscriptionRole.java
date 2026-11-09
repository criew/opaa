package io.opaa.llm.transcription;

import io.opaa.instance.ScheduledRunLocks;
import io.opaa.llm.ModelEndpointUri;
import io.opaa.llm.ModelRoleObservations;
import io.opaa.llm.ModelRoleSettingsChangedEvent;
import io.opaa.llm.OptionalRoleState;
import io.opaa.llm.OptionalRoleStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The live upstream of the transcription role (ADR-0072): whether text may appear while a person
 * speaks, and the opening of one live session. Live is offered only on top of a usable batch
 * endpoint, which stays the fallback: {@link #available()} needs the role {@link
 * OptionalRoleState#READY}, a stored live upstream whose last probe succeeded and a free session of
 * the installation ({@link RealtimeTranscriptionSlots}).
 *
 * <p>The last known state is the installation's ({@link
 * ModelRoleObservations#TRANSCRIPTION_REALTIME}, ADR-0053, Entscheidung 12); the instance holding
 * the probe lock probes, on the role's own probe thread, at startup, after every change and every
 * minute. A probe opens a session and closes it without audio, which costs nothing at the
 * providers.
 */
@Service
public class RealtimeTranscriptionRole implements RealtimeTranscriptionStatusResolver {

  private static final Logger log = LoggerFactory.getLogger(RealtimeTranscriptionRole.class);

  private static final long PROBE_INTERVAL_MILLIS = 60_000;

  private final TranscriptionModelRole role;
  private final Supplier<TranscriptionEndpoint> endpoints;
  private final RealtimeTranscriptionClient client;
  private final RealtimeTranscriptionSlots slots;
  private final ModelRoleObservations observations;
  private final TaskScheduler probeScheduler;
  private final Clock clock;
  private final TranscriptionProperties properties;

  @Autowired
  public RealtimeTranscriptionRole(
      TranscriptionModelRole role,
      TranscriptionEndpoints endpoints,
      RealtimeTranscriptionClient client,
      RealtimeTranscriptionSlots slots,
      ModelRoleObservations observations,
      @Qualifier("transcriptionProbeScheduler") TaskScheduler probeScheduler,
      Clock clock,
      TranscriptionProperties properties) {
    this(role, endpoints::current, client, slots, observations, probeScheduler, clock, properties);
  }

  public RealtimeTranscriptionRole(
      TranscriptionModelRole role,
      Supplier<TranscriptionEndpoint> endpoints,
      RealtimeTranscriptionClient client,
      RealtimeTranscriptionSlots slots,
      ModelRoleObservations observations,
      TaskScheduler probeScheduler,
      Clock clock,
      TranscriptionProperties properties) {
    this.role = role;
    this.endpoints = endpoints;
    this.client = client;
    this.slots = slots;
    this.observations = observations;
    this.probeScheduler = probeScheduler;
    this.clock = clock;
    this.properties = properties;
  }

  /** No live session could be opened; nothing was sent. */
  public static class RealtimeUnusableException extends RuntimeException {

    private final boolean busy;

    public RealtimeUnusableException(String message, boolean busy) {
      super(message);
      this.busy = busy;
    }

    /** Whether the person or the installation is at its limit, as opposed to live being off. */
    public boolean busy() {
      return busy;
    }
  }

  /** One live session: the upstream connection and the slot it holds. */
  public record LiveSession(
      RealtimeTranscriptionClient.Session upstream,
      RealtimeTranscriptionSlots.Slot slot,
      int sampleRate) {

    /** Closes the upstream and frees the slot. */
    public void close() {
      try {
        upstream.close();
      } finally {
        slot.close();
      }
    }
  }

  /** The state of the stored live upstream; never probes. */
  public OptionalRoleStatus status() {
    return realtimeStatusFor(endpoints.get());
  }

  @Override
  public OptionalRoleStatus realtimeStatusFor(TranscriptionEndpoint endpoint) {
    RealtimeTranscriptionEndpoint realtime = endpoint.realtime();
    if (!role.featureEnabled() || !endpoint.enabled() || !realtime.configured()) {
      return OptionalRoleStatus.disabled();
    }
    if (ModelEndpointUri.containsCredentials(realtime.url())) {
      return new OptionalRoleStatus(
          OptionalRoleState.UNCONFIGURED,
          null,
          realtime.model(),
          "the stored live address carries credentials and was rejected",
          false,
          true);
    }
    return observations
        .current(ModelRoleObservations.TRANSCRIPTION_REALTIME)
        .filter(known -> known.isFor(realtime.url(), observedModel(realtime)))
        .map(
            known ->
                known.reachable()
                    ? ready(realtime)
                    : unreachable(realtime, known.diagnostic(), known.timedOut()))
        .orElseGet(() -> unreachable(realtime, "live upstream not probed yet", false));
  }

  /**
   * Whether a live session may be opened right now. Never probes; reads the open sessions of the
   * installation, and answers {@code false} while the database cannot tell.
   */
  public boolean available() {
    TranscriptionEndpoint endpoint = endpoints.get();
    if (role.statusFor(endpoint).state() != OptionalRoleState.READY
        || realtimeStatusFor(endpoint).state() != OptionalRoleState.READY) {
      return false;
    }
    try {
      return slots.hasFree(endpoint.realtime().maxSessions());
    } catch (DataAccessException e) {
      log.warn("The open live transcription sessions could not be counted: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Opens a live session for {@code person}: takes a slot, connects to the upstream and sends the
   * opening of the dialect, with the installation's and the person's vocabulary. Blocks for the
   * handshake. A connection that does not reach the upstream moves the live state to {@link
   * OptionalRoleState#UNREACHABLE}.
   *
   * @throws RealtimeUnusableException when live is not available or a limit is reached
   * @throws RealtimeTranscriptionClient.RealtimeUnavailableException when the upstream failed
   */
  public LiveSession open(
      UUID person, String personalVocabulary, RealtimeTranscriptionClient.Listener listener) {
    TranscriptionEndpoint endpoint = endpoints.get();
    RealtimeTranscriptionEndpoint realtime = endpoint.realtime();
    if (role.statusFor(endpoint).state() != OptionalRoleState.READY
        || realtimeStatusFor(endpoint).state() != OptionalRoleState.READY) {
      throw new RealtimeUnusableException("live transcription is not available", false);
    }
    RealtimeTranscriptionSlots.Opening opening = slots.open(person, realtime.maxSessions());
    if (opening.slot() == null) {
      throw new RealtimeUnusableException("no live session free: " + opening.refusal(), true);
    }
    try {
      String vocabulary = TranscriptionVocabulary.prompt(endpoint.vocabulary(), personalVocabulary);
      RealtimeTranscriptionClient.Session upstream =
          client.open(
              realtime, endpoint.language(), vocabulary == null ? "" : vocabulary, listener);
      record(realtime, ready(realtime), false);
      return new LiveSession(upstream, opening.slot(), client.sampleRate(realtime.protocol()));
    } catch (RealtimeTranscriptionClient.RealtimeUnavailableException e) {
      opening.slot().close();
      if (!e.answered()) {
        record(realtime, unreachable(realtime, e.getMessage(), e.timedOut()), false);
      }
      log.warn(
          "Live transcription against {} could not be opened: {}",
          realtime.describeWithoutSecrets(),
          e.getMessage());
      throw e;
    } catch (RuntimeException e) {
      opening.slot().close();
      throw e;
    }
  }

  /** Probes the stored live upstream and records the result; blocking. */
  public OptionalRoleStatus refresh() {
    TranscriptionEndpoint endpoint = endpoints.get();
    RealtimeTranscriptionEndpoint realtime = endpoint.realtime();
    if (!role.featureEnabled()
        || !endpoint.enabled()
        || !realtime.configured()
        || ModelEndpointUri.containsCredentials(realtime.url())) {
      return realtimeStatusFor(endpoint);
    }
    RealtimeTranscriptionClient.ProbeFailure failure = client.probe(realtime, properties.timeout());
    OptionalRoleStatus probed =
        failure == null
            ? ready(realtime)
            : unreachable(realtime, failure.message(), failure.timedOut());
    record(realtime, probed, true);
    return probed;
  }

  /** Probes and logs a configured but unusable live upstream at {@code ERROR}. */
  void probeAndReport() {
    OptionalRoleStatus status = refresh();
    if (status.state() == OptionalRoleState.UNREACHABLE) {
      log.error(
          "Live transcription is configured but not usable ({}): {}. Dictation falls back to"
              + " recording.",
          endpoints.get().realtime().describeWithoutSecrets(),
          status.diagnostic());
    }
  }

  @Scheduled(
      fixedDelay = PROBE_INTERVAL_MILLIS,
      initialDelay = PROBE_INTERVAL_MILLIS,
      scheduler = "transcriptionProbeScheduler")
  @SchedulerLock(
      name = "transcription-realtime-probe",
      lockAtLeastFor = "PT50S",
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_SHORT)
  public void probePeriodically() {
    refresh();
  }

  /** Probes once the application is up, off the event thread. */
  @EventListener
  void onApplicationReady(ApplicationReadyEvent event) {
    if (role.featureEnabled()) {
      probeScheduler.schedule(this::probeAndReport, Instant.now(clock));
    }
  }

  /** A change in the model administration is probed at once, off the request thread. */
  @EventListener
  void onSettingsChanged(ModelRoleSettingsChangedEvent event) {
    if (event.role() == ModelRoleSettingsChangedEvent.Role.TRANSCRIPTION && role.featureEnabled()) {
      probeScheduler.schedule(this::probeAndReport, Instant.now(clock));
    }
  }

  private void record(
      RealtimeTranscriptionEndpoint realtime, OptionalRoleStatus status, boolean renew) {
    try {
      observations.record(
          ModelRoleObservations.TRANSCRIPTION_REALTIME,
          new ModelRoleObservations.ModelRoleObservation(
              realtime.url(),
              observedModel(realtime),
              status.state() == OptionalRoleState.READY,
              status.timedOut(),
              status.diagnostic(),
              null),
          renew);
    } catch (DataAccessException e) {
      log.warn("The state of the live transcription could not be recorded: {}", e.getMessage());
    }
  }

  /** The model as the observation keys it: a change of dialect is a different upstream. */
  static String observedModel(RealtimeTranscriptionEndpoint realtime) {
    return realtime.protocol() + " " + realtime.model();
  }

  private static OptionalRoleStatus ready(RealtimeTranscriptionEndpoint realtime) {
    return new OptionalRoleStatus(OptionalRoleState.READY, realtime.url(), realtime.model(), null);
  }

  private static OptionalRoleStatus unreachable(
      RealtimeTranscriptionEndpoint realtime, String diagnostic, boolean timedOut) {
    return new OptionalRoleStatus(
        OptionalRoleState.UNREACHABLE, realtime.url(), realtime.model(), diagnostic, timedOut);
  }
}
