package io.opaa.llm;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * configuration with reranking (issue #1050, docs/features/retrieval-benchmark.md §2).
 *
 * <p><b>Neither {@link #currentStatus()} nor {@link #usable()} ever probes.</b> Both answer from
 * configuration and the last known endpoint state: the first is on the path of an administration
 * page load, the second on the path of every query, and neither may pay for a network round trip.
 * The state is kept current by {@link #refresh()} - once at startup, then on a fixed schedule - and
 * by every real {@link #rerank} call, which records what actually happened.
 */
@Service
public class RerankModelRole implements RerankRoleStatusProvider {

  private static final Logger log = LoggerFactory.getLogger(RerankModelRole.class);

  /** How often the endpoint is re-probed while nothing else exercises it. */
  private static final long PROBE_INTERVAL_MILLIS = 60_000;

  private final RerankProperties properties;
  private final RerankClient client;

  /**
   * The last probe or call result for a switched-on, bound role; {@code null} until the first
   * probe. The switched-off and unbound states are derived from configuration instead, so they can
   * never go stale.
   */
  private final AtomicReference<RerankRoleStatus> lastKnown = new AtomicReference<>();

  /**
   * How many {@link #rerank} calls came back without a usable ranking since this process started -
   * a failed call as well as an endpoint that answered with an empty ranking. Exactly the calls for
   * which {@code RerankStage} reports {@code UNAVAILABLE} and falls back to the fused order. Never
   * reset: a caller that wants "did anything degrade in between?" compares two readings.
   */
  private final AtomicLong degradedCalls = new AtomicLong();

  public RerankModelRole(RerankProperties properties, RerankClient client) {
    this.properties = properties;
    this.client = client;
  }

  @Override
  public RerankRoleStatus currentStatus() {
    if (!properties.enabled()) {
      return RerankRoleStatus.disabled();
    }
    if (!properties.bound()) {
      return new RerankRoleStatus(
          RerankRoleState.UNCONFIGURED,
          emptyToNull(properties.baseUrl()),
          emptyToNull(properties.model()),
          "rerank role switched on, but opaa.rerank.base-url and/or opaa.rerank.model are unset");
    }
    RerankRoleStatus known = lastKnown.get();
    return known != null ? known : status(RerankRoleState.UNREACHABLE, "endpoint not probed yet");
  }

  /**
   * Keeps the state current while nothing else exercises the endpoint - so a role that dies in
   * operation shows up as {@link RerankRoleState#UNREACHABLE} without waiting for the next query.
   * "Shows up" is bounded by {@link RerankProperties#timeout()}, not by {@link
   * #PROBE_INTERVAL_MILLIS}: a probe against a hanging endpoint can itself take up to that budget
   * (240s by default, see #1154) before it counts as failed, so the honest bound on a dead
   * endpoint's detection is one probe interval plus one timeout - and the first in-flight query
   * pays up to one more timeout before it falls back, exactly as this probe does.
   */
  @Scheduled(
      fixedDelay = PROBE_INTERVAL_MILLIS,
      initialDelay = PROBE_INTERVAL_MILLIS,
      scheduler = "rerankProbeScheduler")
  void probePeriodically() {
    refresh();
  }

  /**
   * Probes the endpoint and records the result. Blocking, and therefore called only from the
   * startup check and the schedule above - never from a query or a page load.
   */
  public RerankRoleStatus refresh() {
    if (!properties.enabled() || !properties.bound()) {
      lastKnown.set(null);
      return currentStatus();
    }
    RerankClient.ProbeFailure failure = client.probe(properties);
    RerankRoleStatus probed =
        failure == null
            ? status(RerankRoleState.READY, null)
            : status(RerankRoleState.UNREACHABLE, failure.message(), failure.timedOut());
    lastKnown.set(probed);
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
    if (!properties.enabled() || !properties.bound()) {
      return List.of();
    }
    try {
      List<RerankClient.ScoredCandidate> scored = client.rerank(properties, query, texts);
      lastKnown.set(status(RerankRoleState.READY, null));
      if (scored.isEmpty() && !texts.isEmpty()) {
        // The endpoint answered, but with nothing the caller can order by - the caller falls back
        // to its incoming order just as it does after a failure, so it counts as one.
        degradedCalls.incrementAndGet();
      }
      return scored;
    } catch (RuntimeException e) {
      // Deliberately every runtime failure, not just RerankUnavailableException: the promise above
      // is absolute, and a client-side fault (an unserializable request body, say) must cost the
      // ordering exactly as an unreachable endpoint does. A timeout is still reported distinctly
      // (#1154): the endpoint answered the connection, it simply did not finish in time.
      degradedCalls.incrementAndGet();
      boolean timedOut = e instanceof RerankClient.RerankTimeoutException;
      lastKnown.set(status(RerankRoleState.UNREACHABLE, e.getMessage(), timedOut));
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
