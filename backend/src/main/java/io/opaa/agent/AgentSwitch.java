package io.opaa.agent;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether agents are switched on right now (ADR-0070, Entscheidung 6). Read from the single row on
 * every call, without a cache: a change holds on every instance from the next call on, so switching
 * off is also an emergency stop that no open session outlives.
 */
@Component
public class AgentSwitch {

  private final AgentSettingsRepository settings;

  AgentSwitch(AgentSettingsRepository settings) {
    this.settings = settings;
  }

  @Transactional(readOnly = true)
  public boolean isEnabled() {
    return settings.findSingleton().map(AgentSettings::isEnabled).orElse(false);
  }
}
