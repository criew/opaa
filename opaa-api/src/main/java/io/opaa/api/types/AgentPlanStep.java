package io.opaa.api.types;

/**
 * Whether a run starts with a plan (ADR-0070, Entscheidung 1). {@link #CONFIRM} ends the run with
 * the plan; the person's confirmation starts a new run.
 */
public enum AgentPlanStep {
  /** The agent starts right away. */
  OFF,
  /** The run ends with the plan; the confirmation starts the next run. */
  CONFIRM
}
