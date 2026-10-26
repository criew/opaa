package io.opaa.connection.token;

import io.opaa.instance.RetiredRetentionVariables;
import io.opaa.instance.RetiredRetentionVariables.Pause;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The thresholds and periods of the connections in force: the value the system administration set,
 * else the factory default of {@link ConnectionLifecycleSetting}. Read from the database on every
 * call, without a cache, so a change holds on every instance from the next decision on (ADR-0053).
 */
@Component
public class ConnectionLifecycleSettings {

  private final ConnectionLifecycleSettingRepository repository;
  private final RetiredRetentionVariables retired;

  public ConnectionLifecycleSettings(
      ConnectionLifecycleSettingRepository repository, RetiredRetentionVariables retired) {
    this.repository = repository;
    this.retired = retired;
  }

  /**
   * Why the erasure after the deletion period pauses (ADR-0060, Entscheidung 13): while the
   * administration stored no period, every live instance that still sets the retired variable to a
   * longer one. A stored value, the factory default included, ends the pause.
   */
  public List<Pause> deletionPauses() {
    ConnectionLifecycleSetting setting = ConnectionLifecycleSetting.PRIVATE_LIBRARY_DELETION_DAYS;
    if (administrationValue(setting).isPresent()) {
      return List.of();
    }
    return retired.longerThan(
        RetiredRetentionVariables.PRIVATE_LIBRARY_DELETION, setting.factoryDefault());
  }

  /** The value in force for {@code setting}. */
  public int value(ConnectionLifecycleSetting setting) {
    return administrationValue(setting).orElse(setting.factoryDefault());
  }

  /** The value the administration set for {@code setting}, empty while the default applies. */
  public Optional<Integer> administrationValue(ConnectionLifecycleSetting setting) {
    return repository.findValue(setting.name());
  }

  /** The stored row of {@code setting}, with the time of its change; empty for the default. */
  public Optional<ConnectionLifecycleSettingRow> stored(ConnectionLifecycleSetting setting) {
    return repository.findById(setting.name());
  }

  /** Days without a sign-in after which a person's connections rest. */
  public Duration inactivityThreshold() {
    return Duration.ofDays(value(ConnectionLifecycleSetting.INACTIVITY_THRESHOLD_DAYS));
  }

  /** How long the private libraries of a deactivated person are kept. */
  public Duration privateLibraryDeletionPeriod() {
    return Duration.ofDays(value(ConnectionLifecycleSetting.PRIVATE_LIBRARY_DELETION_DAYS));
  }

  /** Expired connected accounts on one profile from which the administration's overview warns. */
  public int expiredConnectionWarningThreshold() {
    return value(ConnectionLifecycleSetting.EXPIRED_CONNECTION_WARNING_THRESHOLD);
  }

  /**
   * Sets {@code setting} to {@code value}; {@code null} returns to the factory default.
   *
   * @throws IllegalArgumentException for a value outside the bounds of {@code setting}
   */
  @Transactional
  public void set(ConnectionLifecycleSetting setting, Integer value) {
    if (value == null) {
      repository.clear(setting.name());
      return;
    }
    if (!setting.admits(value)) {
      throw new IllegalArgumentException(setting + " out of range: " + value);
    }
    repository.upsert(setting.name(), value);
  }
}
