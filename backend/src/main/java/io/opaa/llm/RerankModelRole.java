package io.opaa.llm;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * afterwards through {@link #status()} - retrieval then runs without reranking, but not unnoticed.
 *
 * <p>The status is probed at startup and refreshed lazily: a read older than {@link #STATUS_TTL}
 * re-probes, and every real call updates it from what actually happened. An endpoint that dies
 * during operation therefore shows up as {@link RerankRoleState#UNREACHABLE} within a minute of the
 * next look, without a scheduler.
 */
@Service
public class RerankModelRole {

  private static final Logger log = LoggerFactory.getLogger(RerankModelRole.class);

  /** How long a probe result is trusted before {@link #status()} probes again. */
  static final Duration STATUS_TTL = Duration.ofSeconds(60);

  private final RerankProperties properties;
  private final RerankClient client;
  private final AtomicReference<RerankRoleStatus> status = new AtomicReference<>();

  public RerankModelRole(RerankProperties properties, RerankClient client) {
    this.properties = properties;
    this.client = client;
  }

  /**
   * The current state, re-probing when the last probe is older than {@link #STATUS_TTL}. Never
   * throws: an unreachable endpoint is a state, not an error.
   */
  public RerankRoleStatus status() {
    if (!properties.enabled() || !properties.bound()) {
      return configurationOnlyStatus();
    }
    RerankRoleStatus current = status.get();
    if (current == null
        || Duration.between(current.checkedAt(), Instant.now()).compareTo(STATUS_TTL) > 0) {
      return refresh();
    }
    return current;
  }

  /** Probes the endpoint now and returns the resulting state. */
  public RerankRoleStatus refresh() {
    if (!properties.enabled() || !properties.bound()) {
      RerankRoleStatus configuration = configurationOnlyStatus();
      status.set(configuration);
      return configuration;
    }
    String failure = client.probeFailureMessage(properties);
    RerankRoleStatus probed =
        failure == null
            ? reachable()
            : unreachable("Der Rerank-Endpunkt hat nicht geantwortet: " + failure);
    status.set(probed);
    return probed;
  }

  /** Whether a query may call the endpoint right now. */
  public boolean usable() {
    return status().usable();
  }

  /**
   * Scores {@code texts} against {@code query}, best first.
   *
   * <p><b>Never throws and never fails a query.</b> A call that does not come back usable yields an
   * empty list and moves the role to {@link RerankRoleState#UNREACHABLE}, so the caller falls back
   * to the ranking it already had and the failure is visible in {@link #status()} afterwards.
   */
  public List<RerankClient.ScoredCandidate> rerank(String query, List<String> texts) {
    if (!properties.enabled() || !properties.bound()) {
      return List.of();
    }
    try {
      List<RerankClient.ScoredCandidate> scored = client.rerank(properties, query, texts);
      status.set(reachable());
      return scored;
    } catch (RerankClient.RerankUnavailableException e) {
      status.set(unreachable("Der letzte Rerank-Aufruf ist fehlgeschlagen: " + e.getMessage()));
      log.warn(
          "Rerank call against {} failed, continuing without reranking: {}",
          properties.describeWithoutSecrets(),
          e.getMessage());
      return List.of();
    }
  }

  private RerankRoleStatus configurationOnlyStatus() {
    if (!properties.enabled()) {
      return new RerankRoleStatus(
          RerankRoleState.DISABLED,
          false,
          properties.baseUrl(),
          properties.model(),
          "Reranking ist ausgeschaltet (OPAA_RERANK_ENABLED). Die Suche läuft ohne Reranking – so"
              + " eingestellt.",
          null);
    }
    if (!properties.bound()) {
      return new RerankRoleStatus(
          RerankRoleState.UNCONFIGURED,
          true,
          properties.baseUrl(),
          properties.model(),
          "Reranking ist eingeschaltet, aber die Rerank-Rolle ist nicht belegt: Basis-Adresse"
              + " und Modell-Kennung müssen beide gesetzt sein (OPAA_RERANK_BASE_URL,"
              + " OPAA_RERANK_MODEL). Die Suche läuft bis dahin ohne Reranking.",
          null);
    }
    throw new IllegalStateException("configurationOnlyStatus called for a bound, enabled role");
  }

  private RerankRoleStatus reachable() {
    return new RerankRoleStatus(
        RerankRoleState.ACTIVE,
        true,
        properties.baseUrl(),
        properties.model(),
        "Reranking ist eingeschaltet und der Endpunkt hat geantwortet.",
        Instant.now());
  }

  private RerankRoleStatus unreachable(String message) {
    return new RerankRoleStatus(
        RerankRoleState.UNREACHABLE,
        true,
        properties.baseUrl(),
        properties.model(),
        message + " Die Suche läuft bis dahin ohne Reranking.",
        Instant.now());
  }
}
