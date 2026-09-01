package io.opaa.llm;

/**
 * The three distinguishable states of the rerank model role (docs/features/hybrid-retrieval.md,
 * "Was die Seite anzeigt"): deliberately switched off, switched on and usable, and switched on but
 * broken. The last one is split into two constants because the two need different remedies, but
 * both are a Störungsmeldung and never a Fußnote.
 */
public enum RerankRoleState {

  /**
   * {@code OPAA_RERANK_ENABLED} is off. An explicit statement, not an unnoticed configuration gap.
   */
  DISABLED,

  /** Switched on, an endpoint and model are configured, and the endpoint answered. */
  READY,

  /** Switched on, but no endpoint or model is configured for the role. */
  UNCONFIGURED,

  /** Switched on and configured, but the endpoint did not answer. */
  UNREACHABLE
}
