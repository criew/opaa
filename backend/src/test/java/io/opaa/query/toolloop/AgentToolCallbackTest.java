package io.opaa.query.toolloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.chat.AgentRunRecord.EndState;
import io.opaa.chat.AgentRunRecord.Step;
import io.opaa.chat.AgentRunRecord.StepStatus;
import io.opaa.metadata.MetadataFilter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The limits of a run are checked before every call; the reminder follows every result. */
class AgentToolCallbackTest {

  private static final Instant START = Instant.parse("2026-11-10T08:00:00Z");
  private static final String REMINDER = "\n\n---\n\nErinnerung";

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final AgentMetrics metrics =
      new AgentMetrics(registry, mock(AgentWorkspace.class), properties());
  private final Clock clock = Clock.fixed(START, ZoneOffset.UTC);

  @Test
  void aCallRunsTheToolCountsTheStepAndAppendsTheReminderBehindTheResult() {
    AgentRun run = AgentRuns.run(Set.of());
    AgentToolCallback callback = callback(new EchoTool("search_knowledge", "Treffer"), clock);

    String result = callback.call("{\"frage\":\"x\"}", context(run));

    assertThat(result).isEqualTo("Treffer" + REMINDER);
    assertThat(run.toolCalls()).isEqualTo(1);
    assertThat(run.steps()).containsExactly(new Step("search_knowledge", "x", 2, StepStatus.DONE));
    assertThat(count("search_knowledge", "success")).isEqualTo(1.0);
  }

  @Test
  void aCallBeyondTheSearchLimitDoesNotRunAndEndsTheRunAtTheLimit() {
    AgentRun run = AgentRuns.run(Set.of());
    AgentToolCallback callback = callback(new EchoTool("search_knowledge", "Treffer"), clock);
    for (int i = 0; i < AgentRuns.LIMITS.maxSearchCalls(); i++) {
      callback.call("{}", context(run));
    }

    String result = callback.call("{}", context(run));

    assertThat(result).startsWith(AgentToolCallback.LIMIT_REACHED);
    assertThat(run.toolCalls()).isEqualTo(3);
    assertThat(run.limitReached()).isEqualTo(EndState.LIMIT_CALLS);
    assertThat(run.steps()).last().extracting(Step::status).isEqualTo(StepStatus.LIMIT);
  }

  @Test
  void aCallAfterTheRunsTimeEndsTheRunAtTheDurationLimit() {
    AgentRun run = AgentRuns.run(Set.of());
    Clock late = Clock.fixed(START.plus(Duration.ofSeconds(121)), ZoneOffset.UTC);

    callback(new EchoTool("read_document", "Text"), late).call("{}", context(run));

    assertThat(run.limitReached()).isEqualTo(EndState.LIMIT_DURATION);
    assertThat(run.toolCalls()).isZero();
  }

  @Test
  void aCallBeyondTheCollectedVolumeEndsTheRunAtTheVolumeLimit() {
    AgentRun run =
        AgentRuns.run(
            Set.of(),
            MetadataFilter.NONE,
            new AgentLimits(5, 3, Duration.ofSeconds(120), 10),
            START);
    AgentToolCallback callback = callback(new EchoTool("read_document", "x".repeat(20)), clock);
    callback.call("{}", context(run));

    callback.call("{}", context(run));

    assertThat(run.limitReached()).isEqualTo(EndState.LIMIT_VOLUME);
    assertThat(run.toolCalls()).isEqualTo(1);
  }

  @Test
  void argumentsThatAreNoJsonObjectAreAnInvalidCallTheModelIsToldAbout() {
    AgentRun run = AgentRuns.run(Set.of());

    String result =
        callback(new EchoTool("search_knowledge", "-"), clock).call("[1]", context(run));

    assertThat(result).startsWith(AgentToolCallback.INVALID_JSON);
    assertThat(count("search_knowledge", "invalid_arguments")).isEqualTo(1.0);
  }

  @Test
  void anInventedToolNameIsCountedAsUnknownWithoutTheInventedName() {
    AgentRun run = AgentRuns.run(Set.of());
    AgentToolCallback unknown =
        AgentToolCallback.unknown(REMINDER, JsonMapper.builder().build(), metrics, clock);

    String result = unknown.call("{}", context(run));

    assertThat(unknown.getToolDefinition().name()).isEqualTo("unknown");
    assertThat(result).startsWith(AgentToolCallback.UNKNOWN_TOOL);
    assertThat(run.steps())
        .containsExactly(new Step("unknown", null, null, StepStatus.UNKNOWN_TOOL));
    assertThat(count("unknown", "unknown_tool")).isEqualTo(1.0);
  }

  @Test
  void aCallOutsideARunsToolContextIsRefused() {
    AgentToolCallback callback = callback(new EchoTool("search_knowledge", "-"), clock);

    assertThatThrownBy(() -> callback.call("{}")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> callback.call("{}", new ToolContext(Map.of())))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aFailingToolIsCountedAsErrorAndEndsTheRun() {
    AgentRun run = AgentRuns.run(Set.of());
    AgentTool failing =
        new EchoTool("search_knowledge", "-") {
          @Override
          public ToolCallOutcome call(JsonNode arguments, AgentRun r) {
            throw new IllegalStateException("retrieval down");
          }
        };

    assertThatThrownBy(() -> callback(failing, clock).call("{}", context(run)))
        .hasMessage("retrieval down");
    assertThat(count("search_knowledge", "error")).isEqualTo(1.0);
  }

  private AgentToolCallback callback(AgentTool tool, Clock at) {
    return AgentToolCallback.of(tool, REMINDER, JsonMapper.builder().build(), metrics, at);
  }

  private static ToolContext context(AgentRun run) {
    return new ToolContext(Map.of(AgentToolCallback.RUN_KEY, run));
  }

  private double count(String tool, String result) {
    var counter =
        registry.find(AgentMetrics.TOOL_CALLS).tag("tool", tool).tag("result", result).counter();
    return counter == null ? 0.0 : counter.count();
  }

  static AgentToolLoopProperties properties() {
    return new AgentToolLoopProperties(
        5, 3, Duration.ofSeconds(120), 60_000, 2, Duration.ofSeconds(60));
  }

  private static class EchoTool implements AgentTool {

    private final String name;
    private final String text;

    EchoTool(String name, String text) {
      this.name = name;
      this.text = text;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public ToolRightsClass rightsClass() {
      return ToolRightsClass.READING;
    }

    @Override
    public String description() {
      return "";
    }

    @Override
    public String inputSchema() {
      return "{}";
    }

    @Override
    public ToolCallOutcome call(JsonNode arguments, AgentRun run) {
      return new ToolCallOutcome(
          text, ToolCallResult.SUCCESS, arguments.path("frage").asString(null), 2, List.of());
    }
  }
}
