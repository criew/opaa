package io.opaa.query.toolloop;

import java.util.Set;

/**
 * The switch of each tool, kept in the database by the agent settings and read at the start of
 * every run, so a change applies to the next run on every instance. Without an implementation every
 * tool counts as switched off - the delivered default.
 */
public interface AgentToolSwitches {

  /** The names of the tools switched on. */
  Set<String> enabledTools();
}
