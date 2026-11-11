package io.opaa.query.toolloop;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The system limits of an agent run and the workspace in front of it (ADR-0070 Entscheidung 6).
 * Conservative defaults, to be calibrated on realistic cases; an agent may only lower the four run
 * limits. The same on every instance: the workspace capacity is the installation's absolute figure,
 * never divided by the number of instances.
 *
 * @param maxToolCalls tool calls per run, over all tools.
 * @param maxSearchCalls calls of {@code search_knowledge} per run.
 * @param maxDuration wall-clock time of a run before its closing answer call.
 * @param maxCollectedChars characters of all tool results of a run together; checked before each
 *     call, so the result that crosses it is still delivered whole.
 * @param workspaceCapacity agent runs at the same time, installation-wide; a request beyond it is
 *     refused before its run begins.
 * @param workspaceCapacityPerPerson agent runs one person may have at the same time, over all
 *     instances; at most {@code workspaceCapacity}. Only below it can no single person take every
 *     place.
 * @param closingCallAllowance the longest a single model call may take; a run's reservation
 *     outlives {@code maxDuration} by twice this - the call running at the limit and the closing
 *     answer call - and the reservation of a crashed instance frees itself after that.
 */
@ConfigurationProperties(prefix = "opaa.agent")
@InstallationWide
@NoDataImpact("limits of a run and of the runs at the same time")
public record AgentToolLoopProperties(
    @DefaultValue("5") int maxToolCalls,
    @DefaultValue("3") int maxSearchCalls,
    @DefaultValue("120s") @NoDataImpact("wall-clock limit of a run") Duration maxDuration,
    @DefaultValue("60000") int maxCollectedChars,
    @DefaultValue("2") int workspaceCapacity,
    @DefaultValue("1") int workspaceCapacityPerPerson,
    @DefaultValue("60s") @NoDataImpact("lifetime of a workspace reservation after the run limit")
        Duration closingCallAllowance)
    implements AgentSystemLimits {

  public AgentToolLoopProperties {
    requirePositive("maxToolCalls", maxToolCalls);
    requirePositive("maxSearchCalls", maxSearchCalls);
    requirePositive("maxCollectedChars", maxCollectedChars);
    requirePositive("workspaceCapacity", workspaceCapacity);
    requirePositive("workspaceCapacityPerPerson", workspaceCapacityPerPerson);
    if (workspaceCapacityPerPerson > workspaceCapacity) {
      throw new IllegalArgumentException(
          "workspaceCapacityPerPerson must not exceed workspaceCapacity, got "
              + workspaceCapacityPerPerson
              + " > "
              + workspaceCapacity);
    }
    if (maxDuration == null || maxDuration.isNegative() || maxDuration.isZero()) {
      throw new IllegalArgumentException("maxDuration must be positive");
    }
    if (closingCallAllowance == null
        || closingCallAllowance.isNegative()
        || closingCallAllowance.isZero()) {
      throw new IllegalArgumentException("closingCallAllowance must be positive");
    }
  }

  @Override
  public AgentLimits current() {
    return new AgentLimits(maxToolCalls, maxSearchCalls, maxDuration, maxCollectedChars);
  }

  /**
   * How long a reservation lives: the run limit, the model call that may still be running at it and
   * the closing answer call, each model call bounded by {@code closingCallAllowance}.
   */
  Duration reservationLifetime() {
    return maxDuration.plus(closingCallAllowance.multipliedBy(2));
  }

  private static void requirePositive(String name, int value) {
    if (value < 1) {
      throw new IllegalArgumentException(name + " must be at least 1, got " + value);
    }
  }
}
