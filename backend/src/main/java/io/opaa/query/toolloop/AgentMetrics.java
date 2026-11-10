package io.opaa.query.toolloop;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The metrics of the agent loop (ADR-0071). Every count is installation-wide: no tag carries an
 * agent, a person, a space, a library, a document or a chat, and {@code kind} only tells the
 * shipped agent from the others. The tool tag takes the registered names and {@code unknown}.
 */
@Component
class AgentMetrics {

  static final String RUNS = "opaa.agent.runs";
  static final String RUN_DURATION = "opaa.agent.run.duration";
  static final String TOOL_CALLS = "opaa.agent.tool.calls";
  static final String WORKSPACE_OCCUPIED = "opaa.agent.workspace.occupied";
  static final String WORKSPACE_CAPACITY = "opaa.agent.workspace.capacity";
  static final String WORKSPACE_REJECTED = "opaa.agent.workspace.rejected";
  static final String CITATIONS_UNVERIFIABLE = "opaa.agent.citations.unverifiable";

  /** The tag value of a tool the model invented. */
  static final String UNKNOWN_TOOL = "unknown";

  private final MeterRegistry registry;
  private final Counter rejected;

  AgentMetrics(
      MeterRegistry registry, AgentWorkspace workspace, AgentToolLoopProperties properties) {
    this.registry = registry;
    this.rejected =
        Counter.builder(WORKSPACE_REJECTED)
            .description("Agent requests refused before the run because the workspace was full")
            .register(registry);
    Gauge.builder(WORKSPACE_OCCUPIED, workspace, AgentWorkspace::occupied)
        .description("Running agent runs holding a workspace reservation, installation-wide")
        .register(registry);
    Gauge.builder(WORKSPACE_CAPACITY, properties, p -> p.workspaceCapacity())
        .description("Configured number of agent runs at the same time, installation-wide")
        .register(registry);
  }

  void recordRun(boolean builtin, AgentRunResult result, Duration duration) {
    Counter.builder(RUNS)
        .tag("kind", kind(builtin))
        .tag("result", result.tagValue())
        .description("Agent runs by outcome")
        .register(registry)
        .increment();
    Timer.builder(RUN_DURATION)
        .tag("kind", kind(builtin))
        .tag("result", result.tagValue())
        .description("Wall-clock time of an agent run, closing answer call included")
        .register(registry)
        .record(duration);
  }

  void recordToolCall(String tool, ToolCallResult result) {
    Counter.builder(TOOL_CALLS)
        .tag("tool", tool)
        .tag("result", result.tagValue())
        .description("Tool calls of agent runs by tool and outcome")
        .register(registry)
        .increment();
  }

  void recordRejected() {
    rejected.increment();
  }

  void recordUnverifiableCitations(boolean builtin, int count) {
    if (count <= 0) {
      return;
    }
    Counter.builder(CITATIONS_UNVERIFIABLE)
        .tag("kind", kind(builtin))
        .description("Citations in agent answers marked as not backed by a passage of the run")
        .register(registry)
        .increment(count);
  }

  private static String kind(boolean builtin) {
    return builtin ? "builtin" : "custom";
  }
}
