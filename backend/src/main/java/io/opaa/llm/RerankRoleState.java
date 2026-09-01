package io.opaa.llm;

/**
 * The four states the rerank model role can be in. Three of them are the reason this state exists
 * at all: without it, "reranking did not happen" looks the same from the outside whether it was
 * switched off on purpose, left unconfigured, or configured against an address that does not answer
 * (docs/features/hybrid-retrieval.md, "'Aus' muss eine Aussage sein, kein Zustand").
 */
public enum RerankRoleState {

  /** The switch is off. Reranking does not run, and that is what the operator asked for. */
  DISABLED,

  /**
   * The switch is on but the role is unbound - no endpoint, no model identifier, or neither. A
   * contradiction: retrieval runs without reranking although reranking was asked for.
   */
  UNCONFIGURED,

  /**
   * The switch is on and the role is bound, but the endpoint did not answer the last probe. Same
   * contradiction as {@link #UNCONFIGURED}, different cause and different fix.
   */
  UNREACHABLE,

  /** The switch is on, the role is bound, and the endpoint answered the last probe. */
  ACTIVE;

  /** Whether this state contradicts the operator's intent and therefore needs to be reported. */
  public boolean contradictsIntent() {
    return this == UNCONFIGURED || this == UNREACHABLE;
  }
}
