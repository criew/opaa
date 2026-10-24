package io.opaa.compliance;

import io.opaa.compliance.ComplianceManifest.DataFlowEntry;
import io.opaa.compliance.ComplianceManifest.DataFlowGroup;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The Mermaid overview of the {@link DataFlowEntry data flows} in the fact sheet {@code
 * datenfluesse}: OPAA in the middle, one subgraph per {@link DataFlowGroup} in enum order, one node
 * per flow sorted by id and labelled with it, so equal manifests give equal diagrams. Links have no
 * arrowhead because the groups of {@link #leftOfOpaa(DataFlowGroup)} are drawn before OPAA; a
 * dashed link takes the outbound proxy. Node ids consist of letters, digits and underscores only.
 */
final class DataFlowDiagram {

  /** The Mermaid id of the OPAA node. */
  static final String OPAA = "opaa";

  private static final Pattern NOT_ID = Pattern.compile("[^A-Za-z0-9]+");

  private DataFlowDiagram() {}

  /** The diagram source without the fence, ending with a line break; empty without flows. */
  static String render(List<DataFlowEntry> flows) {
    if (flows.isEmpty()) {
      return "";
    }
    Map<DataFlowGroup, Map<String, DataFlowEntry>> grouped = new EnumMap<>(DataFlowGroup.class);
    flows.forEach(
        flow -> grouped.computeIfAbsent(flow.group(), g -> new TreeMap<>()).put(flow.id(), flow));

    Set<String> usedIds = new HashSet<>(Set.of(OPAA));
    Map<DataFlowEntry, String> nodeIds = new IdentityHashMap<>();
    StringBuilder out = new StringBuilder("flowchart LR\n");
    out.append("    ").append(OPAA).append("([\"OPAA\"])\n");
    grouped.forEach(
        (group, entries) -> {
          out.append("    subgraph group_")
              .append(group.name().toLowerCase(Locale.ROOT))
              .append("[\"")
              .append(title(group))
              .append("\"]\n");
          entries
              .values()
              .forEach(
                  flow -> {
                    String nodeId = nodeId(flow.id(), usedIds);
                    nodeIds.put(flow, nodeId);
                    out.append("        ")
                        .append(nodeId)
                        .append("[\"")
                        .append(label(flow.id()))
                        .append("\"]\n");
                  });
          out.append("    end\n");
        });
    grouped.forEach(
        (group, entries) ->
            entries
                .values()
                .forEach(
                    flow -> {
                      String link =
                          Boolean.TRUE.equals(flow.viaOutboundProxy()) ? " -.- " : " --- ";
                      String node = nodeIds.get(flow);
                      out.append("    ");
                      if (leftOfOpaa(group)) {
                        out.append(node).append(link).append(OPAA);
                      } else {
                        out.append(OPAA).append(link).append(node);
                      }
                      out.append('\n');
                    }));
    return out.toString();
  }

  /** The German title of a group, as the diagram and the tables of the sheet show it. */
  static String title(DataFlowGroup group) {
    return switch (group) {
      case MODEL -> "Modelle";
      case CONNECTOR -> "Konnektoren und Zugänge";
      case MAIL -> "Mail";
      case IDENTITY -> "Identität";
      case STORAGE -> "Speicher";
      case SECURITY -> "Sicherheit";
    };
  }

  /** Whether the group is drawn left of OPAA, so OPAA sits between two columns of targets. */
  static boolean leftOfOpaa(DataFlowGroup group) {
    return switch (group) {
      case MODEL, MAIL, IDENTITY -> true;
      case CONNECTOR, STORAGE, SECURITY -> false;
    };
  }

  /**
   * A Mermaid node id for a flow id: {@code flow_} and the id with every run of other characters
   * than ASCII letters and digits an underscore, made unique against {@code used} by a suffix.
   */
  static String nodeId(String flowId, Set<String> used) {
    String base = "flow_" + NOT_ID.matcher(flowId).replaceAll("_");
    String candidate = base;
    for (int suffix = 2; !used.add(candidate); suffix++) {
      candidate = base + "_" + suffix;
    }
    return candidate;
  }

  /**
   * Text safe inside a quoted Mermaid label: Mermaid's entity codes for the characters that end the
   * label, start an entity, turn it into Markdown or HTML; line breaks become spaces.
   */
  static String label(String value) {
    StringBuilder out = new StringBuilder();
    for (char c : value.replaceAll("\\s*\\R\\s*", " ").strip().toCharArray()) {
      switch (c) {
        case '#' -> out.append("#35;");
        case '"' -> out.append("#quot;");
        case '<' -> out.append("#lt;");
        case '>' -> out.append("#gt;");
        case '&' -> out.append("#amp;");
        case '`' -> out.append("#96;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }
}
