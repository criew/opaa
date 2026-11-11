package io.opaa.llm;

/**
 * An optional model role as the administration stored it (ADR-0060, Entscheidung 10): the switch
 * states the intent, address and model the how. The switch is deliberately separate from the
 * endpoint fields: a role being off must be a statement, never the indistinguishable consequence of
 * a missing address (ADR-0065, Entscheidung 6).
 */
public interface OptionalRoleEndpoint {

  boolean enabled();

  /** Stripped, empty while unset. */
  String baseUrl();

  /** Stripped, empty while unset or for a role connected without a model. */
  String model();

  /** Whether everything the role needs to be called is present. */
  boolean bound();

  /** Endpoint and model as far as they may be logged: never the key, never userinfo. */
  String describeWithoutSecrets();

  /**
   * The model as the shared role observation keys it; an observation counts only for the address
   * and this value it was made for.
   */
  default String observedModel() {
    return model();
  }
}
