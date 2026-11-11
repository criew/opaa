package io.opaa.api.types;

/**
 * An installation-wide right, granted to accounts, groups or to all accounts at once (ADR-0036,
 * Entscheidung 5). The {@code CREATE_} values are rights to <em>create</em> something
 * ("Anlegerecht" in the user interface) and open no existing content; {@link #VIEW_AGENT_DIRECTORY}
 * is the one right to read, and it reads descriptions, never knowledge. No value of this enum may
 * widen what {@code LibraryAccessService#readableLibraryIds} returns.
 *
 * <p>A closed vocabulary, mirrored by the database check constraints {@code
 * chk_capability_grants_capability} and {@code chk_capability_grant_history_capability}; keep both
 * in sync.
 */
public enum Capability {
  /**
   * Creating a space. The personal space is provisioned at first sign-in, independently of this.
   */
  CREATE_SPACE,

  /** Creating a knowledge library whose documents are uploaded. */
  CREATE_LIBRARY,

  /**
   * Creating a knowledge library fed by a connector. Its own capability because such a library
   * reaches server paths and stored credentials. The only capability with a scope - one connector
   * type ({@code TYPE:<key>}) or one connection profile ({@code PROFILE:<id>}) - so releasing one
   * target never opens another (ADR-0036, Entscheidung 5).
   */
  CREATE_CONNECTOR_LIBRARY,

  /**
   * Creating an internal group. Delivered to nobody; a system administrator holds it implicitly.
   */
  CREATE_INTERNAL_GROUP,

  /**
   * Creating a prompt library. Delivered to all accounts: a prompt binds no knowledge and reaches
   * no source, so creating one widens nothing.
   */
  CREATE_PROMPT_LIBRARY,

  /**
   * Creating an agent. Delivered to nobody: an agent runs the tool loop for whoever calls it, so
   * the system administration names who may build one. Offered only while the installation switch
   * for agents is on.
   */
  CREATE_AGENT,

  /**
   * Creating a document template. Delivered to nobody: a template shapes what agents hand out as
   * files, so who may add one is a deliberate decision of the installation.
   */
  CREATE_TEMPLATE,

  /**
   * Reading the agent directory: every agent and document template of the organization with
   * purpose, instruction, library names, tools, limits, responsible unit, revision and lock state,
   * without library contents and without usage figures. Delivered to nobody; meant for the staff
   * council. Offered only while the installation switch for agents is on.
   */
  VIEW_AGENT_DIRECTORY
}
