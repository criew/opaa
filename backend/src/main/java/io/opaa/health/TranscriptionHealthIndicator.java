package io.opaa.health;

import io.opaa.llm.TranscriptionRoleStatus;
import io.opaa.llm.TranscriptionRoleStatusProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports the transcription role (#1926) from its last known state, without a call of its own. A
 * switched-off role is {@code UP}: dictation absent by decision is no fault. Lives in its own
 * health group, so a dead transcription endpoint never takes the instance out of rotation.
 */
@Component
@ConditionalOnProperty(name = "management.health.transcription.enabled", matchIfMissing = true)
public class TranscriptionHealthIndicator implements HealthIndicator {

  private final TranscriptionRoleStatusProvider role;

  public TranscriptionHealthIndicator(TranscriptionRoleStatusProvider role) {
    this.role = role;
  }

  @Override
  public Health health() {
    TranscriptionRoleStatus status = role.currentStatus();
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
}
