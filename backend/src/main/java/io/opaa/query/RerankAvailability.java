package io.opaa.query;

import io.opaa.llm.RerankRoleState;

/**
 * What the rerank model role can contribute to one retrieval run, decided once when the {@link
 * RetrievalContext} is built.
 *
 * <p><b>Three states, not a boolean.</b> "Switched off" and "switched on but not usable" are
 * different statements about an installation - the first is an operator's decision, the second a
 * Störung - and a run's explanation protocol must be able to tell them apart
 * (docs/features/hybrid-retrieval.md, "'Aus' muss eine Aussage sein, kein Zustand"). A boolean
 * collapses both into the same {@link StageStatus#DISABLED}.
 */
public enum RerankAvailability {

  /** {@code OPAA_RERANK_ENABLED} is off: the installation deliberately does not rerank. */
  SWITCHED_OFF,

  /**
   * Switched on, but the role has no endpoint/model configured or its endpoint did not answer. The
   * run continues without reranking and reports {@link StageStatus#UNAVAILABLE}.
   */
  NOT_USABLE,

  /** Switched on, configured, and the endpoint last answered - a query may call it. */
  USABLE;

  /** The role's own state, expressed as what a retrieval run can do with it. */
  public static RerankAvailability of(RerankRoleState state) {
    return switch (state) {
      case DISABLED -> SWITCHED_OFF;
      case READY -> USABLE;
      case UNCONFIGURED, UNREACHABLE -> NOT_USABLE;
    };
  }
}
