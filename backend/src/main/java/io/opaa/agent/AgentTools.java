package io.opaa.agent;

import java.util.List;
import java.util.Set;

/**
 * The tools an agent may be given in the first cut (ADR-0070). Stored by name; the tool loop offers
 * a run only the tools it has registered and switched on.
 */
public final class AgentTools {

  public static final String SEARCH_KNOWLEDGE = "search_knowledge";
  public static final String READ_DOCUMENT = "read_document";
  public static final String LIST_SOURCES = "list_sources";
  public static final String RUN_CODE = "run_code";
  public static final String ASK_USER = "ask_user";

  /** Offered to a run only while the agent has a template the asker may fill. */
  public static final String FILL_TEMPLATE = "fill_template";

  /** Every assignable tool, in the order the interface lists them. */
  public static final List<String> ASSIGNABLE =
      List.of(SEARCH_KNOWLEDGE, READ_DOCUMENT, LIST_SOURCES, RUN_CODE, ASK_USER, FILL_TEMPLATE);

  /** What a new agent starts with: searching and reading. */
  public static final Set<String> DEFAULT = Set.of(SEARCH_KNOWLEDGE, READ_DOCUMENT);

  private AgentTools() {}
}
