package io.opaa.query.toolloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ToolRegistryTest {

  @Test
  void listsEveryToolWithItsRightsClassByName() {
    ToolRegistry registry =
        new ToolRegistry(
            List.of(
                new FakeTool("search_knowledge", ToolRightsClass.READING),
                new FakeTool("run_code", ToolRightsClass.ISOLATED)));

    assertThat(registry.tools())
        .containsExactly(
            new AgentToolInfo("run_code", ToolRightsClass.ISOLATED),
            new AgentToolInfo("search_knowledge", ToolRightsClass.READING));
  }

  @Test
  void aToolWithoutRightsClassKeepsTheApplicationFromStarting() {
    assertThatThrownBy(() -> new ToolRegistry(List.of(new FakeTool("search_knowledge", null))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("rights class");
  }

  @Test
  void twoToolsWithTheSameNameKeepTheApplicationFromStarting() {
    assertThatThrownBy(
            () ->
                new ToolRegistry(
                    List.of(
                        new FakeTool("search_knowledge", ToolRightsClass.READING),
                        new FakeTool("search_knowledge", ToolRightsClass.READING))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("share the name");
  }

  @Test
  void theNameOfTheUnknownToolTagIsNoToolName() {
    assertThatThrownBy(
            () -> new ToolRegistry(List.of(new FakeTool("unknown", ToolRightsClass.READING))))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new ToolRegistry(List.of(new FakeTool(" ", ToolRightsClass.READING))))
        .isInstanceOf(IllegalStateException.class);
  }

  private record FakeTool(String name, ToolRightsClass rightsClass) implements AgentTool {

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
      return ToolCallOutcome.refused(ToolCallResult.ERROR, "");
    }
  }
}
