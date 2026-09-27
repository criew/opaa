package io.opaa.architecture;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Tarjan's algorithm: the strongly connected component of every node of a directed graph. */
final class StronglyConnectedComponents {

  private final Map<String, ? extends Map<String, ?>> graph;
  private final Map<String, Integer> index = new HashMap<>();
  private final Map<String, Integer> lowLink = new HashMap<>();
  private final Deque<String> stack = new ArrayDeque<>();
  private final Set<String> onStack = new HashSet<>();
  private final Map<String, Integer> component = new HashMap<>();
  private int nextIndex;
  private int nextComponent;

  private StronglyConnectedComponents(Map<String, ? extends Map<String, ?>> graph) {
    this.graph = graph;
  }

  /**
   * The component number of every node, source or target; two nodes share a number exactly when
   * each reaches the other.
   */
  static Map<String, Integer> of(Map<String, ? extends Map<String, ?>> graph) {
    StronglyConnectedComponents algorithm = new StronglyConnectedComponents(graph);
    graph.keySet().forEach(algorithm::visitIfNew);
    graph.values().forEach(targets -> targets.keySet().forEach(algorithm::visitIfNew));
    return algorithm.component;
  }

  private void visitIfNew(String node) {
    if (!index.containsKey(node)) {
      visit(node);
    }
  }

  private void visit(String node) {
    index.put(node, nextIndex);
    lowLink.put(node, nextIndex);
    nextIndex++;
    stack.push(node);
    onStack.add(node);
    Map<String, ?> targets = graph.get(node);
    for (String target : targets == null ? Set.<String>of() : targets.keySet()) {
      if (!index.containsKey(target)) {
        visit(target);
        lowLink.put(node, Math.min(lowLink.get(node), lowLink.get(target)));
      } else if (onStack.contains(target)) {
        lowLink.put(node, Math.min(lowLink.get(node), index.get(target)));
      }
    }
    if (lowLink.get(node).equals(index.get(node))) {
      String member;
      do {
        member = stack.pop();
        onStack.remove(member);
        component.put(member, nextComponent);
      } while (!member.equals(node));
      nextComponent++;
    }
  }
}
