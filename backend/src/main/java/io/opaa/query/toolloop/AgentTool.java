package io.opaa.query.toolloop;

import java.util.Optional;
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
   * Description and argument schema as this run offers them to the model, asked once before the
   * run's first model call. By default the fixed ones; a tool whose arguments depend on what the
   * asker may use right now builds them here and checks them again on every call. Empty when the
   * tool has nothing to offer this run; the model then does not see it.
   */
  default Optional<ToolOffer> offer(AgentRun run) {
    return Optional.of(new ToolOffer(description(), inputSchema()));
  }

  /**
   * Runs one call. A failure of a service behind the tool is thrown and ends the run with an error;
   * an argument the tool cannot use is an outcome, not an exception.
   */
  ToolCallOutcome call(JsonNode arguments, AgentRun run);

  /**
   * What a call with {@code arguments} is about in the person's words, shown while it runs - the
   * same text the outcome's detail will carry, never a raw argument such as an id. {@code null}
   * when the tool knows it only after the call or has none.
   */
  default String startDetail(JsonNode arguments) {
    return null;
  }
}
