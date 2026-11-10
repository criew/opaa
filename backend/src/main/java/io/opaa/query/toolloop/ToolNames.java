package io.opaa.query.toolloop;

/**
 * The names of the tools of the first cut (ADR-0070). They are the names the model sees, the values
 * an agent stores and the values of the metric tag {@code tool}; renaming one breaks stored agents.
 */
public final class ToolNames {

  public static final String SEARCH_KNOWLEDGE = "search_knowledge";
  public static final String READ_DOCUMENT = "read_document";
  public static final String LIST_SOURCES = "list_sources";
  public static final String RUN_CODE = "run_code";
  public static final String ASK_USER = "ask_user";
  public static final String FILL_TEMPLATE = "fill_template";

  private ToolNames() {}
}
