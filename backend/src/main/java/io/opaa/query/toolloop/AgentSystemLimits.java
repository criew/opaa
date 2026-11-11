package io.opaa.query.toolloop;

/**
 * The installation's limits of a run, as the agent editor checks them: an agent may only lower
 * them.
 */
public interface AgentSystemLimits {

  /** The system limits, every component set. */
  AgentLimits current();
}
