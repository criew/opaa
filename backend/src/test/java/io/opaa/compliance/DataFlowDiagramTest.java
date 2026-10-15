package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.ComplianceManifest.DataFlowEntry;
import io.opaa.compliance.ComplianceManifest.DataFlowGroup;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The generated overview of the data flows: every flow of the shipped manifest is a node linked to
 * OPAA, the source does not depend on the order of the manifest, and node ids and labels stay valid
 * Mermaid whatever an id contains.
 */
class DataFlowDiagramTest {

  private static final ComplianceManifest SHIPPED = ComplianceManifestLoader.load();

  private static final Pattern NODE = Pattern.compile("^ {8}(\\S+)\\[\"([^\"]*)\"]$");

  @Test
  void everyDataFlowOfTheShippedManifestIsANodeLinkedToOpaa() {
    String diagram = DataFlowDiagram.render(SHIPPED.dataFlows());

    assertThat(SHIPPED.dataFlows()).isNotEmpty();
    for (DataFlowEntry flow : SHIPPED.dataFlows()) {
      String node = nodeOf(diagram, flow.id());
      String link = Boolean.TRUE.equals(flow.viaOutboundProxy()) ? " -.- " : " --- ";
      String expected =
          DataFlowDiagram.leftOfOpaa(flow.group())
              ? node + link + DataFlowDiagram.OPAA
              : DataFlowDiagram.OPAA + link + node;
      assertThat(diagram.lines()).as(flow.id()).contains("    " + expected);
    }
    assertThat(nodes(diagram)).hasSize(SHIPPED.dataFlows().size());
  }

  @Test
  void theSourceIsTheSameForEveryOrderOfTheManifest() {
    List<DataFlowEntry> shuffled = new ArrayList<>(SHIPPED.dataFlows());
    Collections.shuffle(shuffled, new Random(2844));

    assertThat(DataFlowDiagram.render(shuffled))
        .isEqualTo(DataFlowDiagram.render(SHIPPED.dataFlows()));
  }

  @Test
  void groupsFollowTheEnumAndNodesTheirIds() {
    String diagram =
        DataFlowDiagram.render(
            List.of(
                flow("zeta", DataFlowGroup.STORAGE, false),
                flow("mail", DataFlowGroup.MAIL, false),
                flow("source-b", DataFlowGroup.CONNECTOR, true),
                flow("source-a", DataFlowGroup.CONNECTOR, true),
                flow("chat", DataFlowGroup.MODEL, true)));

    assertThat(diagram)
        .isEqualTo(
            """
            flowchart LR
                opaa(["OPAA"])
                subgraph group_model["Modelle"]
                    flow_chat["chat"]
                end
                subgraph group_connector["Konnektoren und Zugänge"]
                    flow_source_a["source-a"]
                    flow_source_b["source-b"]
                end
                subgraph group_mail["Mail"]
                    flow_mail["mail"]
                end
                subgraph group_storage["Speicher"]
                    flow_zeta["zeta"]
                end
                flow_chat -.- opaa
                opaa -.- flow_source_a
                opaa -.- flow_source_b
                flow_mail --- opaa
                opaa --- flow_zeta
            """);
  }

  @Test
  void nodeIdsAreMermaidSafeAndUniqueAndLabelsEscaped() {
    String diagram =
        DataFlowDiagram.render(
            List.of(
                flow("a-b", DataFlowGroup.CONNECTOR, true),
                flow("a_b", DataFlowGroup.CONNECTOR, true),
                flow("end", DataFlowGroup.CONNECTOR, true),
                flow("x\"] --> y[\"<b>#1</b> & `z`", DataFlowGroup.CONNECTOR, true)));

    List<String[]> nodes = nodes(diagram);
    assertThat(nodes).hasSize(4);
    assertThat(nodes)
        .extracting(node -> node[0])
        .allMatch(id -> id.matches("[A-Za-z0-9_]+"))
        .doesNotHaveDuplicates()
        .doesNotContain("end", DataFlowDiagram.OPAA);
    assertThat(nodes)
        .extracting(node -> node[1])
        .contains(
            "a-b",
            "a_b",
            "end",
            "x#quot;] --#gt; y[#quot;#lt;b#gt;#35;1#lt;/b#gt; #amp; #96;z#96;");
  }

  @Test
  void noFlowsGiveNoDiagram() {
    assertThat(DataFlowDiagram.render(List.of())).isEmpty();
  }

  private static DataFlowEntry flow(String id, DataFlowGroup group, boolean viaOutboundProxy) {
    return new DataFlowEntry(
        "outbound", id, group, viaOutboundProxy, "Ziel", "Daten", "Anlass", "Aus", List.of(), null);
  }

  private static String nodeOf(String diagram, String flowId) {
    return nodes(diagram).stream()
        .filter(node -> node[1].equals(DataFlowDiagram.label(flowId)))
        .map(node -> node[0])
        .findFirst()
        .orElseThrow(() -> new AssertionError("no node labelled " + flowId + " in\n" + diagram));
  }

  private static List<String[]> nodes(String diagram) {
    List<String[]> nodes = new ArrayList<>();
    diagram
        .lines()
        .forEach(
            line -> {
              Matcher matcher = NODE.matcher(line);
              if (matcher.matches()) {
                nodes.add(new String[] {matcher.group(1), matcher.group(2)});
              }
            });
    return nodes;
  }
}
