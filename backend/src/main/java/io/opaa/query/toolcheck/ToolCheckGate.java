package io.opaa.query.toolcheck;

/**
 * Whether tool checks are offered: only while agents are switched on. Implemented by the agent
 * package, which sits above this one; asked on every request, so a change of the switch holds on
 * every instance from the next request on.
 */
public interface ToolCheckGate {

  /** Whether the installation offers tool checks right now. */
  boolean open();
}
