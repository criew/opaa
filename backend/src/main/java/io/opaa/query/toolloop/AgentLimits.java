package io.opaa.query.toolloop;

import java.time.Duration;

/**
 * The limits of one run. In {@link AgentRunSpec} every {@code null} component falls back to the
 * default of an agent without limits of its own, and no component goes above the system limit;
 * {@link AgentSystemLimits#current()} sets all four.
 *
 * @param maxToolCalls tool calls per run, over all tools.
 * @param maxSearchCalls calls of {@code search_knowledge} per run.
 * @param maxDuration wall-clock time of a run, without the closing answer call.
 * @param maxCollectedChars characters of all tool results of a run together - the context that
 *     grows with every step.
 */
public record AgentLimits(
    Integer maxToolCalls, Integer maxSearchCalls, Duration maxDuration, Integer maxCollectedChars) {

  /** No limit of its own: every component falls back to {@link #DEFAULT}, capped by the system. */
  public static final AgentLimits SYSTEM = new AgentLimits(null, null, null, null);

  /**
   * What an agent without a limit of its own gets, as before the system limits were raised: an
   * agent saved without limits keeps its behaviour whatever the system maximum is, and only an
   * explicit value (a delivered agent's, an owner's) goes above it, up to the system limit.
   */
  public static final AgentLimits DEFAULT = new AgentLimits(5, 3, Duration.ofSeconds(120), 60_000);

  /**
   * The limits that apply: each component of {@code requested} that is set, otherwise the default
   * ({@link #DEFAULT}); never above {@code system}'s.
   */
  static AgentLimits effective(AgentLimits system, AgentLimits requested) {
    AgentLimits asked = requested == null ? SYSTEM : requested;
    return new AgentLimits(
        capped(system.maxToolCalls(), asked.maxToolCalls(), DEFAULT.maxToolCalls()),
        capped(system.maxSearchCalls(), asked.maxSearchCalls(), DEFAULT.maxSearchCalls()),
        cappedDuration(system.maxDuration(), asked.maxDuration()),
        capped(system.maxCollectedChars(), asked.maxCollectedChars(), DEFAULT.maxCollectedChars()));
  }

  private static Integer capped(Integer system, Integer requested, Integer fallback) {
    Integer wanted = requested != null ? requested : fallback;
    return wanted != null && wanted < system ? wanted : system;
  }

  private static Duration cappedDuration(Duration system, Duration requested) {
    Duration wanted = requested != null ? requested : DEFAULT.maxDuration();
    return wanted.compareTo(system) < 0 ? wanted : system;
  }
}
