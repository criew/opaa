package io.opaa.query.toolloop;

import io.opaa.chat.AgentRunRecord;
import io.opaa.chat.AgentRunRecord.EndState;
import io.opaa.chat.AgentRunRecord.StepStatus;
import java.time.Clock;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The bridge between Spring AI's tool execution and an {@link AgentTool} (ADR-0070). Before every
 * call it checks the run's limits - a call that would cross one does not run, the run is marked as
 * ended at that limit and the model is told to answer now. After the call it counts the step and
 * appends the reminder of the binding rules behind the result's data block, outside it, so the
 * reminder is the last thing the model reads before the next model call (Entscheidung 5, Teil 8).
 * The run comes from the {@link ToolContext}, never from an argument.
 *
 * <p>Without a tool it stands for a name the model invented: its definition carries the name {@code
 * unknown}, so the tool observation of Spring AI never takes an invented name as a tag value.
 */
final class AgentToolCallback implements ToolCallback {

  static final String RUN_KEY = "opaa.agent.run";

  static final String LIMIT_REACHED =
      "Die Grenze dieses Laufs ist erreicht; der Aufruf wurde nicht ausgeführt. Rufe kein Werkzeug"
          + " mehr auf und antworte jetzt mit dem bisher Gefundenen.";

  static final String UNKNOWN_TOOL =
      "Dieses Werkzeug gibt es nicht. Nutze nur die angebotenen Werkzeuge.";

  static final String INVALID_JSON = "Die Argumente sind kein gültiges JSON-Objekt.";

  private final AgentTool tool;
  private final ToolDefinition definition;
  private final String reminder;
  private final ObjectMapper objectMapper;
  private final AgentMetrics metrics;
  private final Clock clock;

  private AgentToolCallback(
      AgentTool tool,
      ToolDefinition definition,
      String reminder,
      ObjectMapper objectMapper,
      AgentMetrics metrics,
      Clock clock) {
    this.tool = tool;
    this.definition = definition;
    this.reminder = reminder;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
    this.clock = clock;
  }

  static AgentToolCallback of(
      AgentTool tool,
      String reminder,
      ObjectMapper objectMapper,
      AgentMetrics metrics,
      Clock clock) {
    return of(
        tool,
        new ToolOffer(tool.description(), tool.inputSchema()),
        reminder,
        objectMapper,
        metrics,
        clock);
  }

  /** The tool with the description and schema {@code offer} gives this run. */
  static AgentToolCallback of(
      AgentTool tool,
      ToolOffer offer,
      String reminder,
      ObjectMapper objectMapper,
      AgentMetrics metrics,
      Clock clock) {
    return new AgentToolCallback(
        tool,
        ToolDefinition.builder()
            .name(tool.name())
            .description(offer.description())
            .inputSchema(offer.inputSchema())
            .build(),
        reminder,
        objectMapper,
        metrics,
        clock);
  }

  static AgentToolCallback unknown(
      String reminder, ObjectMapper objectMapper, AgentMetrics metrics, Clock clock) {
    return new AgentToolCallback(
        null,
        ToolDefinition.builder()
            .name(AgentMetrics.UNKNOWN_TOOL)
            .description(UNKNOWN_TOOL)
            .inputSchema("{\"type\":\"object\"}")
            .build(),
        reminder,
        objectMapper,
        metrics,
        clock);
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return definition;
  }

  @Override
  public String call(String toolInput) {
    throw new IllegalStateException("An agent tool runs only inside a run's tool context");
  }

  @Override
  public String call(String toolInput, ToolContext toolContext) {
    if (toolContext == null || !(toolContext.getContext().get(RUN_KEY) instanceof AgentRun run)) {
      throw new IllegalStateException("An agent tool runs only inside a run's tool context");
    }
    String name = definition.name();
    EndState limit = run.limitBefore(name, clock.instant());
    if (limit != null) {
      run.reachLimit(limit);
      run.recordStep(new AgentRunRecord.Step(name, null, null, StepStatus.LIMIT));
      run.traceStep(name, StepStatus.LIMIT.name(), null, 0, limit.name());
      return LIMIT_REACHED + reminder;
    }
    JsonNode arguments = arguments(toolInput);
    // Announced before it runs: a reader that went away ends the run here, before the call.
    run.startStep(name, tool == null || arguments == null ? null : tool.startDetail(arguments));
    long started = System.nanoTime();
    ToolCallOutcome outcome;
    try {
      outcome = execute(arguments, run);
    } catch (RuntimeException e) {
      run.traceStep(name, StepStatus.ERROR.name(), null, millisSince(started), "TOOL_FAILED");
      throw e;
    }
    run.traceStep(
        name, outcome.result().stepStatus().name(), outcome.hits(), millisSince(started), null);
    metrics.recordToolCall(name, outcome.result());
    run.recordCall(name, outcome);
    return outcome.modelText() + reminder;
  }

  private static long millisSince(long startedNanos) {
    return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
  }

  /** The call's arguments as a JSON object, {@code null} when they are none. */
  private JsonNode arguments(String toolInput) {
    JsonNode arguments;
    try {
      arguments =
          objectMapper.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
    } catch (JacksonException e) {
      return null;
    }
    return arguments != null && arguments.isObject() ? arguments : null;
  }

  private ToolCallOutcome execute(JsonNode arguments, AgentRun run) {
    if (tool == null) {
      return ToolCallOutcome.refused(ToolCallResult.UNKNOWN_TOOL, UNKNOWN_TOOL);
    }
    if (arguments == null) {
      return ToolCallOutcome.refused(ToolCallResult.INVALID_ARGUMENTS, INVALID_JSON);
    }
    try {
      return tool.call(arguments, run);
    } catch (RuntimeException e) {
      metrics.recordToolCall(definition.name(), ToolCallResult.ERROR);
      throw e;
    }
  }
}
