package io.opaa.health;

import io.opaa.llm.OptionalRoleStatus;
import io.opaa.llm.OptionalRoleStatusProvider;
import io.opaa.observability.LocalHealthGroup;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports an optional model role from its last known state, without a call of its own. A
 * switched-off role is {@code UP}: a role absent by decision is no fault. Each role lives in a
 * health group of its own, so a dead endpoint never takes the instance out of rotation.
 */
public abstract class OptionalRoleHealthIndicator implements HealthIndicator, LocalHealthGroup {

  private final OptionalRoleStatusProvider role;
  private final String healthGroup;

  protected OptionalRoleHealthIndicator(OptionalRoleStatusProvider role, String healthGroup) {
    this.role = role;
    this.healthGroup = healthGroup;
  }

  @Override
  public Health health() {
    OptionalRoleStatus status = role.currentStatus();
    Health.Builder builder =
        switch (status.state()) {
          case DISABLED, READY -> Health.up();
          case UNCONFIGURED, UNREACHABLE -> Health.down();
        };
    builder.withDetail("state", status.state().name());
    if (status.baseUrl() != null) {
      builder.withDetail("baseUrl", status.baseUrl());
    }
    if (status.modelIdentifier() != null) {
      builder.withDetail("modelIdentifier", status.modelIdentifier());
    }
    if (status.diagnostic() != null) {
      builder.withDetail("diagnostic", status.diagnostic());
    }
    return builder.build();
  }

  @Override
  public String healthGroup() {
    return healthGroup;
  }
}
