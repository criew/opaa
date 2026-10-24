package io.opaa.llm;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The last known state of a probed model role - rerank or transcription - shared by every instance
 * of an installation (ADR-0053, Entscheidung 12). Written by the instance that probes the endpoint
 * or calls it for real, read by every instance: a query decides on reranking, and the status page
 * shows the role, the same way whichever instance answers.
 */
public interface ModelRoleObservations {

  /** The role of the rerank model; stored as the key of its row. */
  String RERANK = "RERANK";

  /** The role of the transcription model. */
  String TRANSCRIPTION = "TRANSCRIPTION";

  /**
   * The state of {@code role} as last recorded by any instance, or empty while none recorded one.
   * Never probes and answers from memory while the invalidation bus listens.
   */
  Optional<ModelRoleObservation> current(String role);

  /** Like {@link #current} but read from the store itself, for the decision whether to probe. */
  Optional<ModelRoleObservation> latest(String role);

  /**
   * Records an outcome for {@code role}. A probe always renews the time of the observation; a real
   * call writes only when the state differs from the recorded one, so a query costs no write.
   */
  void record(String role, ModelRoleObservation outcome, boolean probe);

  /** The time {@link ModelRoleObservation#observedAt()} is measured against. */
  Instant now();

  /**
   * One recorded outcome. {@code baseUrl} and {@code model} name the endpoint it was observed at:
   * an outcome recorded for another endpoint says nothing about the configured one.
   *
   * @param observedAt when it was recorded; ignored by {@link #record}, which stamps it itself
   */
  record ModelRoleObservation(
      String baseUrl,
      String model,
      boolean reachable,
      boolean timedOut,
      String diagnostic,
      Instant observedAt) {

    /** Whether this outcome was observed at {@code baseUrl} and {@code model}. */
    public boolean isFor(String expectedBaseUrl, String expectedModel) {
      return baseUrl.equals(expectedBaseUrl) && model.equals(expectedModel);
    }

    /** Whether {@code other} names the same endpoint and state, whenever it was observed. */
    public boolean sameStateAs(ModelRoleObservation other) {
      return isFor(other.baseUrl(), other.model())
          && reachable == other.reachable()
          && timedOut == other.timedOut()
          && Objects.equals(diagnostic, other.diagnostic());
    }
  }
}
