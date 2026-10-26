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
import java.util.function.Supplier;
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
 * <p>Switch, address, model and key are the administration's ({@link ModelRoleEndpoints}, ADR-0060,
 * Entscheidung 10) and are read on every call, so a change takes effect without a restart; timeout
 * and probe interval are the operation's ({@link RerankProperties}).
 *
 * <p><b>A contradiction is never resolved silently.</b> Switch on with the role unbound or its
 * endpoint unreachable is reported at startup ({@link RerankRoleStartupCheck}) and stays readable
 * afterwards through {@link #currentStatus()} - retrieval then runs without reranking, but not
 * unnoticed.
 *
 * <p><b>A call that came back without a ranking is counted, not only remembered</b> ({@link
 * #degradedCallCount()}). The last known state alone cannot answer "did reranking work throughout
 * this measurement?": an endpoint that fails and recovers looks {@link RerankRoleState#READY}
 * afterwards (docs/features/retrieval-benchmark.md §2).
 *
 * <p><b>Neither {@link #currentStatus()} nor {@link #usable()} ever probes.</b> Both answer from
 * the stored endpoint and the last known endpoint state. The state is kept current by every real
 * {@link #rerank} call and by {@link #refresh()} - once at startup, then whenever the last result
 * is older than {@link RerankProperties#probeInterval()} ({@link #probePeriodically()}).
 *
 * <p><b>The last known state is the installation's</b> ({@link ModelRoleObservations}, ADR-0053,
 * Entscheidung 12): whichever instance probes or calls the endpoint records it, and every instance
 * decides on it. An observation counts only for the endpoint and model it was made for.
 */
@Service
public class RerankModelRole implements RerankRoleStatusProvider, RerankRoleStatusResolver {

  private static final Logger log = LoggerFactory.getLogger(RerankModelRole.class);

  /** How often {@link #probePeriodically()} checks whether a probe is due; costs no request. */
  private static final long TICK_MILLIS = 5_000;

  /**
   * How soon an {@link RerankRoleState#UNREACHABLE} role probes again, capped by the probe
   * interval: queries skip the endpoint in that state, so only a probe can bring it back.
   */
  static final Duration RETRY_INTERVAL = Duration.ofSeconds(60);

  private final RerankProperties properties;
  private final Supplier<RerankEndpoint> endpoints;
  private final RerankClient client;
  private final Clock clock;
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
   * a failed call as well as an endpoint that answered with an empty ranking. Never reset; counted
   * per instance, like the measurement that reads it.
   */
  private final AtomicLong degradedCalls = new AtomicLong();

  /** A role whose state lives in this process only - for a context without a database. */
  public RerankModelRole(
      RerankProperties properties,
      Supplier<RerankEndpoint> endpoints,
      RerankClient client,
      Clock clock) {
    this(properties, endpoints, client, clock, new ProcessLocalModelRoleObservations(clock));
  }

  @Autowired
  public RerankModelRole(
      RerankProperties properties,
      ModelRoleEndpoints endpoints,
      RerankClient client,
      Clock clock,
      ModelRoleObservations observations) {
    this(properties, endpoints::rerank, client, clock, observations);
  }

  public RerankModelRole(
      RerankProperties properties,
      Supplier<RerankEndpoint> endpoints,
      RerankClient client,
      Clock clock,
      ModelRoleObservations observations) {
    this.properties = properties;
    this.endpoints = endpoints;
    this.client = client;
    this.clock = clock;
    this.observations = observations;
    Duration half = properties.probeInterval().dividedBy(2);
    this.renewEvery = half.compareTo(Duration.ofMinutes(1)) < 0 ? half : Duration.ofMinutes(1);
  }

  @Override
  public RerankRoleStatus currentStatus() {
    return statusFor(endpoints.get());
  }

  /** The state of the role for {@code endpoint}, from the last known observation; never probes. */
  @Override
  public RerankRoleStatus statusFor(RerankEndpoint endpoint) {
    if (!endpoint.enabled()) {
      return RerankRoleStatus.disabled();
    }
    if (ModelEndpointUri.containsCredentials(endpoint.baseUrl())) {
      return new RerankRoleStatus(
          RerankRoleState.UNCONFIGURED,
          null,
          emptyToNull(endpoint.model()),
          "rerank role switched on, but its base URL carries credentials and was rejected",
          false,
          true);
    }
    if (!endpoint.bound()) {
      return new RerankRoleStatus(
          RerankRoleState.UNCONFIGURED,
          emptyToNull(endpoint.baseUrl()),
          emptyToNull(endpoint.model()),
          "rerank role switched on, but no base URL and/or model is stored for it");
    }
    return observations
        .current(ModelRoleObservations.RERANK)
        .filter(known -> known.isFor(endpoint.baseUrl(), endpoint.model()))
        .map(known -> statusOf(endpoint, known))
        .orElseGet(() -> status(endpoint, RerankRoleState.UNREACHABLE, "endpoint not probed yet"));
  }

  /**
   * Probes once the last result - probe or real call - is older than {@link
   * RerankProperties#probeInterval()}, or older than {@link #RETRY_INTERVAL} while the role is not
   * {@link RerankRoleState#READY}. Detecting a dead endpoint without traffic therefore takes up to
   * one interval plus one {@link RerankProperties#timeout()}.
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
    RerankEndpoint endpoint = endpoints.get();
    if (!callable(endpoint)) {
      return;
    }
    Optional<ModelRoleObservation> last =
        observations
            .latest(ModelRoleObservations.RERANK)
            .filter(known -> known.isFor(endpoint.baseUrl(), endpoint.model()));
    if (last.isEmpty()) {
      refresh(endpoint);
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
      refresh(endpoint);
    }
  }

  /**
   * Probes the endpoint and records the result. Blocking, and therefore called only from the
   * startup check and the schedule above - never from a query or a page load.
   */
  public RerankRoleStatus refresh() {
    return refresh(endpoints.get());
  }

  private RerankRoleStatus refresh(RerankEndpoint endpoint) {
    if (!callable(endpoint)) {
      return statusFor(endpoint);
    }
    RerankClient.ProbeFailure failure = client.probe(endpoint, properties.timeout());
    RerankRoleStatus probed =
        failure == null
            ? status(endpoint, RerankRoleState.READY, null)
            : status(endpoint, RerankRoleState.UNREACHABLE, failure.message(), failure.timedOut());
    record(endpoint, probed, true);
    return probed;
  }

  /** Whether a query may call the endpoint right now. Never blocks. */
  public boolean usable() {
    return currentStatus().state() == RerankRoleState.READY;
  }

  /**
   * The number of {@link #rerank} calls that did not come back with a usable ranking since this
   * process started - see {@link #degradedCalls}. Monotonically increasing.
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
    RerankEndpoint endpoint = endpoints.get();
    if (!callable(endpoint) || texts.isEmpty()) {
      // Nothing to score means no contact with the endpoint, and so no sign of life to record.
      return List.of();
    }
    try {
      List<RerankClient.ScoredCandidate> scored =
          client.rerank(endpoint, properties.timeout(), query, texts);
      recordSuccessfulCall(endpoint);
      if (scored.isEmpty()) {
        // The endpoint answered, but with nothing the caller can order by - the caller falls back
        // to its incoming order just as it does after a failure, so it counts as one.
        degradedCalls.incrementAndGet();
      }
      return scored;
    } catch (RuntimeException e) {
      // Every runtime failure, not just RerankUnavailableException: the promise above is absolute.
      degradedCalls.incrementAndGet();
      boolean timedOut = e instanceof RerankClient.RerankTimeoutException;
      record(
          endpoint, status(endpoint, RerankRoleState.UNREACHABLE, e.getMessage(), timedOut), false);
      log.warn(
          "Rerank call against {} failed, continuing without reranking: {}",
          endpoint.describeWithoutSecrets(),
          e.getMessage(),
          e);
      return List.of();
    }
  }

  /** Switched on, bound, and with an address the role may contact. */
  private static boolean callable(RerankEndpoint endpoint) {
    return endpoint.enabled()
        && endpoint.bound()
        && !ModelEndpointUri.containsCredentials(endpoint.baseUrl());
  }

  /**
   * A successful call: a change of state is written at once, an unchanged ready state renews the
   * recorded time only every {@link #renewEvery}, by this process's clock.
   */
  private void recordSuccessfulCall(RerankEndpoint endpoint) {
    Instant now = clock.instant();
    Instant renewed = lastRenewal.get();
    boolean renew = now.isBefore(renewed) || !now.isBefore(renewed.plus(renewEvery));
    if (renew) {
      lastRenewal.set(now);
    }
    record(endpoint, status(endpoint, RerankRoleState.READY, null), renew);
  }

  /** Records an outcome; a store that cannot take it costs the record, never the query. */
  private void record(RerankEndpoint endpoint, RerankRoleStatus status, boolean renew) {
    try {
      observations.record(
          ModelRoleObservations.RERANK,
          new ModelRoleObservation(
              endpoint.baseUrl(),
              endpoint.model(),
              status.state() == RerankRoleState.READY,
              status.timedOut(),
              status.diagnostic(),
              null),
          renew);
    } catch (DataAccessException e) {
      log.warn("Rerank role state could not be recorded: {}", e.getMessage());
    }
  }

  private RerankRoleStatus statusOf(RerankEndpoint endpoint, ModelRoleObservation known) {
    return known.reachable()
        ? status(endpoint, RerankRoleState.READY, null)
        : status(endpoint, RerankRoleState.UNREACHABLE, known.diagnostic(), known.timedOut());
  }

  private static RerankRoleStatus status(
      RerankEndpoint endpoint, RerankRoleState state, String diagnostic) {
    return status(endpoint, state, diagnostic, false);
  }

  private static RerankRoleStatus status(
      RerankEndpoint endpoint, RerankRoleState state, String diagnostic, boolean timedOut) {
    return new RerankRoleStatus(state, endpoint.baseUrl(), endpoint.model(), diagnostic, timedOut);
  }

  private static String emptyToNull(String value) {
    return value.isEmpty() ? null : value;
  }
}
