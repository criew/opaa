package io.opaa.query.spike;

import io.opaa.compliance.InstanceStateSource;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Whether the data flow {@code code-sandbox} is switched on, for the disclosure: only with both
 * switches of {@link RunCodeTool}. Present with the spike off as well, so the answer is "off"
 * rather than none.
 */
@Component
class SandboxInstanceState implements InstanceStateSource {

  private final boolean active;

  SandboxInstanceState(Environment environment) {
    this.active =
        environment.getProperty("opaa.spike.tool-loop.enabled", Boolean.class, false)
            && environment.getProperty("opaa.sandbox.enabled", Boolean.class, false);
  }

  @Override
  public Map<String, Boolean> dataFlows(UUID organizationId) {
    return Map.of("code-sandbox", active);
  }
}
