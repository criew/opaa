package io.opaa.agent;

/**
 * The limits of one agent's run; {@code null} leaves the installation's limit in force. A run takes
 * the lower of a value and the installation's limit, so a value can only lower a limit.
 *
 * @param maxToolCalls tool calls per run
 * @param maxSearchCalls calls of the search tool per run
 * @param maxDurationSeconds wall-clock time of a run
 * @param maxCollectedChars characters of all tool results of a run
 */
public record AgentLimits(
    Integer maxToolCalls,
    Integer maxSearchCalls,
    Integer maxDurationSeconds,
    Integer maxCollectedChars) {

  /** Every limit left to the installation. */
  public static final AgentLimits INSTALLATION = new AgentLimits(null, null, null, null);
}
