package io.opaa.llm;

import io.opaa.instance.ScheduledRunLocks;
import io.opaa.llm.ModelRoleObservations.ModelRoleObservation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The rerank model role: the single entry point for both questions anyone has about reranking -
 * "may a query use it?" and "what state is it in?" (docs/features/hybrid-retrieval.md, Arbeitspaket
 * 4). Sits on the same level as the chat role's {@link ActiveChatModelResolver} and the embedding
 * role's {@link EmbeddingInfoService}, and like them owns the whole role rather than spreading it
 * over the callers.
 *
 * <p><b>A contradiction is never resolved silently.</b> Switch on with the role unbound or its
 * endpoint unreachable is reported at startup ({@link RerankRoleStartupCheck}) and stays readable
 * afterwards through {@link #currentStatus()} - retrieval then runs without reranking, but not
 * unnoticed.
 *
 * <p><b>A call that came back without a ranking is counted, not only remembered</b> ({@link
 * #degradedCallCount()}). The last known state alone cannot answer "did reranking work throughout
 * this measurement?": an endpoint that fails and recovers looks {@link RerankRoleState#READY}
 * afterwards, and a run measured across that outage would be reported under the name of the
 * configuration with reranking (docs/features/retrieval-benchmark.md §2).
 *
 * <p><b>Neither {@link #currentStatus()} nor {@link #usable()} ever probes.</b> Both answer from
 * configuration and the last known endpoint state: the first is on the path of an administration
 * page load, the second on the path of every query, and neither may pay for a network round trip.
 * The state is kept current by every real {@link #rerank} call, which records what actually
 * happened, and by {@link #refresh()} - once at startup, then whenever the last result is older
 * than {@link RerankProperties#probeInterval()} ({@link #probePeriodically()}).
 *
 * <p><b>The last known state is the installation's</b> ({@link ModelRoleObservations}, ADR-0053,
 * Entscheidung 12): whichever instance probes or calls the endpoint records it, and every instance
 * decides on it, so the same question is reranked or not whichever instance answers it. A real call
 * that keeps the role ready renews the recorded time at most every {@link #renewEvery} and writes
 * nothing otherwise.
 */
@Service
public class RerankModelRole implements RerankRoleStatusProvider {

  private static final Logger log = LoggerFactory.getLogger(RerankModelRole.class);

  /** How often {@link #probePeriodically()} checks whether a probe is due; costs no request. */
  private static final long TICK_MILLIS = 5_000;

  /**
   * How soon an {@link RerankRoleState#UNREACHABLE} role probes again, capped by the probe
   * interval: queries skip the endpoint in that state, so only a probe can bring it back.
   */
  static final Duration RETRY_INTERVAL = Duration.ofSeconds(60);

  private final RerankProperties properties;
  private final RerankClient client;
  private final Clock clock;

  /**
   * Whether {@code opaa.rerank.base-url} carries userinfo. Such an address leaves the role unusable
   * exactly as an unset one does; the application still starts, for the same reason a broken
   * endpoint does not fail it.
   */
  private final boolean baseUrlRejected;

  private final ModelRoleObservations observations;

  /**
   * How often a successful real call renews the recorded time, so that traffic replaces the
   * scheduled probe without a write per query: half the probe interval, at most a minute.
   */
  private final Duration renewEvery;

  /** When this process last renewed the recorded time; read and written by this process only. */
  private final AtomicReference<Instant> lastRenewal = new AtomicReference<>(Instant.MIN);

  /**
   * How many {@link #rerank} calls came back without a usable ranking since this process started -
   * a failed call as well as an endpoint that answered with an empty ranking. Exactly the calls for
   * which {@code RerankStage} reports {@code UNAVAILABLE} and falls back to the fused order. Never
   * reset: a caller that wants "did anything degrade in between?" compares two readings. Counted
   * per instance, like the measurement that reads it.
   */
  private final AtomicLong degradedCalls = new AtomicLong();

  /** A role whose state lives in this process only - for a context without a database. */
  public RerankModelRole(RerankProperties properties, RerankClient client, Clock clock) {
    this(properties, client, clock, new ProcessLocalModelRoleObservations(clock));
  }

  @Autowired
  public RerankModelRole(
      RerankProperties properties,
      RerankClient client,
      Clock clock,
      ModelRoleObservations observations) {
    this.properties = properties;
    this.client = client;
    this.clock = clock;
    this.observations = observations;
    this.baseUrlRejected = ModelEndpointUri.containsCredentials(properties.baseUrl());
    Duration half = properties.probeInterval().dividedBy(2);
    this.renewEvery = half.compareTo(Duration.ofMinutes(1)) < 0 ? half : Duration.ofMinutes(1);
  }

  @Override
  public RerankRoleStatus currentStatus() {
    if (!properties.enabled()) {
      return RerankRoleStatus.disabled();
    }
    if (baseUrlRejected) {
      return new RerankRoleStatus(
          RerankRoleState.UNCONFIGURED,
          null,
          emptyToNull(properties.model()),
          "rerank role switched on, but opaa.rerank.base-url carries credentials and was rejected",
          false,
          true);
    }
    if (!properties.bound()) {
      return new RerankRoleStatus(
          RerankRoleState.UNCONFIGURED,
          emptyToNull(properties.baseUrl()),
          emptyToNull(properties.model()),
          "rerank role switched on, but opaa.rerank.base-url and/or opaa.rerank.model are unset");
    }
    return observations
        .current(ModelRoleObservations.RERANK)
        .filter(known -> known.isFor(properties.baseUrl(), properties.model()))
        .map(this::statusOf)
        .orElseGet(() -> status(RerankRoleState.UNREACHABLE, "endpoint not probed yet"));
  }

  /**
   * Probes once the last result - probe or real call - is older than {@link
   * RerankProperties#probeInterval()}, or older than {@link #RETRY_INTERVAL} while the role is not
   * {@link RerankRoleState#READY}. Detecting a dead endpoint without traffic therefore takes up to
   * one interval plus one {@link RerankProperties#timeout()}, the time a probe against a hanging
   * endpoint needs to count as failed.
   */
  @Scheduled(
      fixedDelay = TICK_MILLIS,
      initialDelay = TICK_MILLIS,
      scheduler = "rerankProbeScheduler")
  @SchedulerLock(
      name = "rerank-role-probe",
      lockAtLeastFor = "PT4S",
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_SHORT)
  public void probePeriodically() {
    if (!properties.enabled() || baseUrlRejected || !properties.bound()) {
      return;
    }
    Optional<ModelRoleObservation> last =
        observations
            .latest(ModelRoleObservations.RERANK)
            .filter(known -> known.isFor(properties.baseUrl(), properties.model()));
    if (last.isEmpty()) {
      refresh();
      return;
    }
    Duration interval = properties.probeInterval();
    Duration due =
        last.get().reachable() || interval.compareTo(RETRY_INTERVAL) < 0
            ? interval
            : RETRY_INTERVAL;
    Instant now = observations.now();
    Instant at = last.get().observedAt();
    // A result stamped in the future means the clock stepped back; probing then keeps the
    // recovery promise instead of waiting out the size of the step.
    if (now.isBefore(at) || !now.isBefore(at.plus(due))) {
      refresh();
    }
  }

  /**
   * Probes the endpoint and records the result. Blocking, and therefore called only from the
   * startup check and the schedule above - never from a query or a page load.
   */
  public RerankRoleStatus refresh() {
    if (!properties.enabled() || baseUrlRejected || !properties.bound()) {
      return currentStatus();
    }
    RerankClient.ProbeFailure failure = client.probe(properties);
    RerankRoleStatus probed =
        failure == null
            ? status(RerankRoleState.READY, null)
            : status(RerankRoleState.UNREACHABLE, failure.message(), failure.timedOut());
    record(probed, true);
    return probed;
  }

  /** Whether a query may call the endpoint right now. Never blocks. */
  public boolean usable() {
    return currentStatus().state() == RerankRoleState.READY;
  }

  /**
   * The number of {@link #rerank} calls that did not come back with a usable ranking since this
   * process started - see {@link #degradedCalls}. Monotonically increasing; two readings around a
   * measurement tell whether reranking held for all of it.
   */
  public long degradedCallCount() {
    return degradedCalls.get();
  }

  /**
   * Scores {@code texts} against {@code query}, best first.
   *
   * <p><b>Never throws and never fails a query.</b> A call that does not come back usable yields an
   * empty list and moves the role to {@link RerankRoleState#UNREACHABLE}, so the caller falls back
   * to the ranking it already had and the failure is visible in {@link #currentStatus()}
   * afterwards.
   */
  public List<RerankClient.ScoredCandidate> rerank(String query, List<String> texts) {
    if (!properties.enabled() || baseUrlRejected || !properties.bound() || texts.isEmpty()) {
      // Nothing to score means no contact with the endpoint, and so no sign of life to record.
      return List.of();
    }
    try {
      List<RerankClient.ScoredCandidate> scored = client.rerank(properties, query, texts);
      recordSuccessfulCall();
      if (scored.isEmpty() && !texts.isEmpty()) {
        // The endpoint answered, but with nothing the caller can order by - the caller falls back
        // to its incoming order just as it does after a failure, so it counts as one.
        degradedCalls.incrementAndGet();
      }
      return scored;
    } catch (RuntimeException e) {
      // Deliberately every runtime failure, not just RerankUnavailableException: the promise above
      // is absolute, and a client-side fault (an unserializable request body, say) must cost the
      // ordering exactly as an unreachable endpoint does. A timeout is still reported distinctly:
      // the endpoint answered the connection, it simply did not finish in time.
      degradedCalls.incrementAndGet();
      boolean timedOut = e instanceof RerankClient.RerankTimeoutException;
      record(status(RerankRoleState.UNREACHABLE, e.getMessage(), timedOut), false);
      // The exception object, not just its message: a programming error caught here (NPE from a
      // client bug, say) has a null message and would otherwise be indistinguishable from a dead
      // endpoint.
      log.warn(
          "Rerank call against {} failed, continuing without reranking: {}",
          properties.describeWithoutSecrets(),
          e.getMessage(),
          e);
      return List.of();
    }
  }

  /**
   * A successful call: a change of state is written at once, an unchanged ready state renews the
   * recorded time only every {@link #renewEvery}, by this process's clock.
   */
  private void recordSuccessfulCall() {
    Instant now = clock.instant();
    Instant renewed = lastRenewal.get();
    boolean renew = now.isBefore(renewed) || !now.isBefore(renewed.plus(renewEvery));
    if (renew) {
      lastRenewal.set(now);
    }
    record(status(RerankRoleState.READY, null), renew);
  }

  /** Records an outcome; a store that cannot take it costs the record, never the query. */
  private void record(RerankRoleStatus status, boolean renew) {
    try {
      observations.record(
          ModelRoleObservations.RERANK,
          new ModelRoleObservation(
              properties.baseUrl(),
              properties.model(),
              status.state() == RerankRoleState.READY,
              status.timedOut(),
              status.diagnostic(),
              null),
          renew);
    } catch (DataAccessException e) {
      log.warn("Rerank role state could not be recorded: {}", e.getMessage());
    }
  }

  private RerankRoleStatus statusOf(ModelRoleObservation known) {
    return known.reachable()
        ? status(RerankRoleState.READY, null)
        : status(RerankRoleState.UNREACHABLE, known.diagnostic(), known.timedOut());
  }

  private RerankRoleStatus status(RerankRoleState state, String diagnostic) {
    return status(state, diagnostic, false);
  }

  private RerankRoleStatus status(RerankRoleState state, String diagnostic, boolean timedOut) {
    return new RerankRoleStatus(
        state, properties.baseUrl(), properties.model(), diagnostic, timedOut);
  }

  private static String emptyToNull(String value) {
    return value.isEmpty() ? null : value;
  }
}
