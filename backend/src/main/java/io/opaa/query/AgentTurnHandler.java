package io.opaa.query;

/**
 * Runs a turn with a chosen agent through the tool loop (ADR-0070) instead of the ordinary path of
 * {@link QueryService}: implemented in {@code io.opaa.query.toolloop}, which depends on this
 * package and not the other way round.
 */
public interface AgentTurnHandler {

  /** Runs and persists the turn and returns its result; refusals are thrown before the run. */
  QueryResult handle(AgentTurn turn);
}
