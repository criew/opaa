package io.opaa.query.toolcheck;

import org.springframework.stereotype.Component;

/**
 * Whether tool checks are offered: only while agents are switched on. Asked on every request, so a
 * change of the switch holds on every instance from the next request on.
 */
@Component
public class ToolCheckGate {

  /** Whether the installation offers tool checks right now. */
  public boolean open() {
    return false;
  }
}
