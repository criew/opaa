package io.opaa.query.toolloop;

import java.time.Duration;

/**
 * The limits of one run. In {@link AgentRunSpec} every {@code null} component falls back to the
 * system limit, and a set one may only lower it; {@link AgentSystemLimits#current()} sets all four.
 *
 * @param maxToolCalls tool calls per run, over all tools.
 * @param maxSearchCalls calls of {@code search_knowledge} per run.
 * @param maxDuration wall-clock time of a run, without the closing answer call.
 * @param maxCollectedChars characters of all tool results of a run together - the context that
 *     grows with every step.
 */
public record AgentLimits(
    Integer maxToolCalls, Integer maxSearchCalls, Duration maxDuration, Integer maxCollectedChars) {

  /** No limit of its own: every component falls back to the system limit. */
  public static final AgentLimits SYSTEM = new AgentLimits(null, null, null, null);

  /**
   * The limits that apply: each component of {@code requested} that is set and lower than {@code
   * system}'s, otherwise the system's. A requested limit never raises a system limit.
   */
  static AgentLimits effective(AgentLimits system, AgentLimits requested) {
    if (requested == null) {
      return system;
    }
    return new AgentLimits(
        lower(system.maxToolCalls(), requested.maxToolCalls()),
        lower(system.maxSearchCalls(), requested.maxSearchCalls()),
        requested.maxDuration() != null
                && requested.maxDuration().compareTo(system.maxDuration()) < 0
            ? requested.maxDuration()
            : system.maxDuration(),
        lower(system.maxCollectedChars(), requested.maxCollectedChars()));
  }

  private static Integer lower(Integer system, Integer requested) {
    return requested != null && requested < system ? requested : system;
  }
}
