package io.opaa.query.toolloop;

import tools.jackson.databind.JsonNode;

/**
 * A tool of the agent loop (ADR-0070, Entscheidung 2): a Spring bean that the {@link ToolRegistry}
 * collects. It checks the caller's rights itself on every call and reaches the bestand only through
 * the domain services of {@code POST /api/v1/query}. Identity, organization and scope come from the
 * {@link AgentRun}, never from {@code arguments}; an argument may only narrow the scope.
 */
public interface AgentTool {

  /** The name the model calls; unique among all tools. */
  String name();

  /** The rights class; {@code null} keeps the application from starting. */
  ToolRightsClass rightsClass();

  /** The description the model decides by; written by the application, never by an agent. */
  String description();

  /** The JSON schema of {@code arguments}. */
  String inputSchema();

  /**
   * Runs one call. A failure of a service behind the tool is thrown and ends the run with an error;
   * an argument the tool cannot use is an outcome, not an exception.
   */
  ToolCallOutcome call(JsonNode arguments, AgentRun run);
}
