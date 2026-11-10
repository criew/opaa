package io.opaa.query.toolloop;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Every {@link AgentTool} of the application, collected after the pattern of {@code
 * SourceConnectorRegistry} (ADR-0070, Entscheidung 2). The application does not start when two
 * tools share a name, a name is blank or the metric tag value {@code unknown}, or a tool declares
 * no rights class: a later tool cannot slip in unclassified.
 */
@Component
public class ToolRegistry {

  private final Map<String, AgentTool> tools;

  ToolRegistry(List<AgentTool> tools) {
    Map<String, AgentTool> byName = new LinkedHashMap<>();
    for (AgentTool tool : tools) {
      String name = tool.name();
      if (name == null || name.isBlank() || AgentMetrics.UNKNOWN_TOOL.equals(name)) {
        throw new IllegalStateException(
            "Agent tool " + tool.getClass().getName() + " has no usable name");
      }
      if (tool.rightsClass() == null) {
        throw new IllegalStateException("Agent tool " + name + " declares no rights class");
      }
      AgentTool previous = byName.putIfAbsent(name, tool);
      if (previous != null) {
        throw new IllegalStateException(
            "Agent tools "
                + previous.getClass().getName()
                + " and "
                + tool.getClass().getName()
                + " share the name "
                + name);
      }
    }
    this.tools = Map.copyOf(byName);
  }

  /** Every registered tool with its rights class, by name. */
  public List<AgentToolInfo> tools() {
    return tools.values().stream()
        .map(tool -> new AgentToolInfo(tool.name(), tool.rightsClass()))
        .sorted(Comparator.comparing(AgentToolInfo::name))
        .toList();
  }

  Optional<AgentTool> find(String name) {
    return Optional.ofNullable(tools.get(name));
  }
}
