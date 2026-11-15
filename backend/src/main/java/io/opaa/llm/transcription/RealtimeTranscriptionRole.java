package io.opaa.llm.transcription;

import io.opaa.instance.ScheduledRunLocks;
import io.opaa.llm.ModelRoleObservations;
import io.opaa.llm.OptionalModelRole;
import io.opaa.llm.OptionalRoleState;
import io.opaa.llm.OptionalRoleStatus;
import java.util.UUID;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
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
 * ModelRoleObservations#TRANSCRIPTION_REALTIME}, ADR-0053, Entscheidung 12), decided by the shared
 * core ({@link OptionalModelRole}) with the batch switch above the live upstream. The role's probe
 * thread probes at startup and after every change ({@code TranscriptionRoleConfiguration}) and,
 * under the probe lock, every minute. A probe opens a session and closes it without audio, which
 * costs nothing at the providers.
 */
@Service
public class RealtimeTranscriptionRole extends OptionalModelRole<RealtimeTranscriptionEndpoint>
    implements RealtimeTranscriptionStatusResolver {

  private static final Logger log = LoggerFactory.getLogger(RealtimeTranscriptionRole.class);

  private static final long PROBE_INTERVAL_MILLIS = 60_000;

  private final TranscriptionModelRole role;
  private final Supplier<TranscriptionEndpoint> endpoints;
  private final RealtimeTranscriptionClient client;
  private final RealtimeTranscriptionSlots slots;
  private final TranscriptionProperties properties;

  @Autowired
  public RealtimeTranscriptionRole(
      TranscriptionModelRole role,
      TranscriptionEndpoints endpoints,
      RealtimeTranscriptionClient client,
      RealtimeTranscriptionSlots slots,
      ModelRoleObservations observations,
      TranscriptionProperties properties) {
    this(role, endpoints::current, client, slots, observations, properties);
  }

  public RealtimeTranscriptionRole(
      TranscriptionModelRole role,
      Supplier<TranscriptionEndpoint> endpoints,
      RealtimeTranscriptionClient client,
      RealtimeTranscriptionSlots slots,
      ModelRoleObservations observations,
      TranscriptionProperties properties) {
    super(
        ModelRoleObservations.TRANSCRIPTION_REALTIME,
        "live transcription",
        () -> liveUpstream(endpoints.get()),
        observations);
    this.role = role;
    this.endpoints = endpoints;
    this.client = client;
    this.slots = slots;
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

  @Override
  public OptionalRoleStatus realtimeStatusFor(TranscriptionEndpoint endpoint) {
    return statusFor(liveUpstream(endpoint));
  }

  /** Live is offered only on the speech-input feature, the role's limit by the operation. */
  @Override
  protected boolean operationAllows() {
    return role.featureEnabled();
  }

  @Override
  protected ProbeFailure probe(RealtimeTranscriptionEndpoint realtime) {
    RealtimeTranscriptionClient.ProbeFailure failure = client.probe(realtime, properties.timeout());
    return failure == null ? null : new ProbeFailure(failure.message(), failure.timedOut());
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

  /** The live upstream as far as the batch switch above it lets it be switched on. */
  private static RealtimeTranscriptionEndpoint liveUpstream(TranscriptionEndpoint endpoint) {
    return endpoint.enabled() ? endpoint.realtime() : RealtimeTranscriptionEndpoint.NONE;
  }
}
