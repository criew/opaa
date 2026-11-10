package io.opaa.llm.rerank;

import io.opaa.instance.ScheduledRunLocks;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.llm.ModelRoleObservations;
import io.opaa.llm.ModelRoleObservations.ModelRoleObservation;
import io.opaa.llm.OptionalModelRole;
import io.opaa.llm.OptionalRoleStartupCheck;
import io.opaa.llm.OptionalRoleState;
import io.opaa.llm.ProcessLocalModelRoleObservations;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The rerank model role: the single entry point for both questions anyone has about reranking -
 * "may a query use it?" and "what state is it in?" (ADR-0065). Sits on the same level as the chat
 * role's {@link ActiveChatModelResolver} and the embedding role's {@code EmbeddingInfoService}, and
 * like them owns the whole role rather than spreading it over the callers.
 *
 * <p>Switch, address, model and key are the administration's ({@link RerankEndpoints}, ADR-0060,
 * Entscheidung 10) and are read on every call, so a change takes effect without a restart; timeout
 * and probe interval are the operation's ({@link RerankProperties}).
 *
 * <p><b>A contradiction is never resolved silently.</b> Switch on with the role unbound or its
 * endpoint unreachable is reported at startup ({@link OptionalRoleStartupCheck}) and stays readable
 * afterwards through {@link #currentStatus()} - retrieval then runs without reranking, but not
 * unnoticed.
 *
 * <p><b>A call that came back without a ranking is counted, not only remembered</b> ({@link
 * #degradedCallCount()}). The last known state alone cannot answer "did reranking work throughout
 * this measurement?": an endpoint that fails and recovers looks {@link OptionalRoleState#READY}
 * afterwards (ADR-0012).
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
public class RerankModelRole extends OptionalModelRole<RerankEndpoint>
    implements RerankRoleStatusProvider {

  private static final Logger log = LoggerFactory.getLogger(RerankModelRole.class);

  /** How often {@link #probePeriodically()} checks whether a probe is due; costs no request. */
  private static final long TICK_MILLIS = 5_000;

  /**
   * How soon an {@link OptionalRoleState#UNREACHABLE} role probes again, capped by the probe
   * interval: queries skip the endpoint in that state, so only a probe can bring it back.
   */
  static final Duration RETRY_INTERVAL = Duration.ofSeconds(60);

  private final RerankProperties properties;
  private final RerankClient client;
  private final Clock clock;

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
      RerankEndpoints endpoints,
      RerankClient client,
      Clock clock,
      ModelRoleObservations observations) {
    this(properties, endpoints::current, client, clock, observations);
  }

  public RerankModelRole(
      RerankProperties properties,
      Supplier<RerankEndpoint> endpoints,
      RerankClient client,
      Clock clock,
      ModelRoleObservations observations) {
    super(ModelRoleObservations.RERANK, "rerank", endpoints, observations);
    this.properties = properties;
    this.client = client;
    this.clock = clock;
    Duration half = properties.probeInterval().dividedBy(2);
    this.renewEvery = half.compareTo(Duration.ofMinutes(1)) < 0 ? half : Duration.ofMinutes(1);
  }

  /**
   * Probes once the last result - probe or real call - is older than {@link
   * RerankProperties#probeInterval()}, or older than {@link #RETRY_INTERVAL} while the role is not
   * {@link OptionalRoleState#READY}. Detecting a dead endpoint without traffic therefore takes up
   * to one interval plus one {@link RerankProperties#timeout()}.
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
    RerankEndpoint endpoint = endpoint();
    if (!callable(endpoint)) {
      return;
    }
    Optional<ModelRoleObservation> last =
        observations()
            .latest(observationKey())
            .filter(known -> known.isFor(endpoint.baseUrl(), endpoint.observedModel()));
    if (last.isEmpty()) {
      refresh(endpoint);
      return;
    }
    Duration interval = properties.probeInterval();
    Duration due =
        last.get().reachable() || interval.compareTo(RETRY_INTERVAL) < 0
            ? interval
            : RETRY_INTERVAL;
    Instant now = observations().now();
    Instant at = last.get().observedAt();
    // A result stamped in the future means the clock stepped back; probing then keeps the
    // recovery promise instead of waiting out the size of the step.
    if (now.isBefore(at) || !now.isBefore(at.plus(due))) {
      refresh(endpoint);
    }
  }

  @Override
  protected ProbeFailure probe(RerankEndpoint endpoint) {
    RerankClient.ProbeFailure failure = client.probe(endpoint, properties.timeout());
    return failure == null ? null : new ProbeFailure(failure.message(), failure.timedOut());
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
   * empty list and moves the role to {@link OptionalRoleState#UNREACHABLE}, so the caller falls
   * back to the ranking it already had and the failure is visible in {@link #currentStatus()}
   * afterwards.
   */
  public List<RerankClient.ScoredCandidate> rerank(String query, List<String> texts) {
    RerankEndpoint endpoint = endpoint();
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
      record(endpoint, unreachable(endpoint, e.getMessage(), timedOut), false);
      log.warn(
          "Rerank call against {} failed, continuing without reranking: {}",
          endpoint.describeWithoutSecrets(),
          e.getMessage(),
          e);
      return List.of();
    }
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
    record(endpoint, ready(endpoint), renew);
  }
}
