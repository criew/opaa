package io.opaa.llm;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The rerank model role: the single entry point for both questions anyone has about reranking - "may
 * a query use it?" and "what state is it in?" (docs/features/hybrid-retrieval.md, Arbeitspaket 4).
 * Sits on the same level as the chat role's {@link ActiveChatModelResolver} and the embedding role's
 * {@link EmbeddingInfoService}, and like them owns the whole role rather than spreading it over the
 * callers.
 *
 * <p><b>A contradiction is never resolved silently.</b> Switch on with the role unbound or its
 * endpoint unreachable is reported at startup ({@link RerankRoleStartupCheck}) and stays readable
 * afterwards through {@link #currentStatus()} - retrieval then runs without reranking, but not
 * unnoticed.
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
   * The last probe or call result for a switched-on, bound role; {@code null} until the first probe.
   * The switched-off and unbound states are derived from configuration instead, so they can never
   * go stale.
   */
  private final AtomicReference<RerankRoleStatus> lastKnown = new AtomicReference<>();

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
   * operation shows up as {@link RerankRoleState#UNREACHABLE} within a minute rather than at the
   * next query.
   */
  @Scheduled(fixedDelay = PROBE_INTERVAL_MILLIS, initialDelay = PROBE_INTERVAL_MILLIS)
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
    String failure = client.probeFailureMessage(properties);
    RerankRoleStatus probed =
        failure == null
            ? status(RerankRoleState.READY, null)
            : status(RerankRoleState.UNREACHABLE, failure);
    lastKnown.set(probed);
    return probed;
  }

  /** Whether a query may call the endpoint right now. Never blocks. */
  public boolean usable() {
    return currentStatus().state() == RerankRoleState.READY;
  }

  /**
   * Scores {@code texts} against {@code query}, best first.
   *
   * <p><b>Never throws and never fails a query.</b> A call that does not come back usable yields an
   * empty list and moves the role to {@link RerankRoleState#UNREACHABLE}, so the caller falls back
   * to the ranking it already had and the failure is visible in {@link #currentStatus()} afterwards.
   */
  public List<RerankClient.ScoredCandidate> rerank(String query, List<String> texts) {
    if (!properties.enabled() || !properties.bound()) {
      return List.of();
    }
    try {
      List<RerankClient.ScoredCandidate> scored = client.rerank(properties, query, texts);
      lastKnown.set(status(RerankRoleState.READY, null));
      return scored;
    } catch (RerankClient.RerankUnavailableException e) {
      lastKnown.set(status(RerankRoleState.UNREACHABLE, e.getMessage()));
      log.warn(
          "Rerank call against {} failed, continuing without reranking: {}",
          properties.describeWithoutSecrets(),
          e.getMessage());
      return List.of();
    }
  }

  private RerankRoleStatus status(RerankRoleState state, String diagnostic) {
    return new RerankRoleStatus(state, properties.baseUrl(), properties.model(), diagnostic);
  }

  private static String emptyToNull(String value) {
    return value.isEmpty() ? null : value;
  }
}
