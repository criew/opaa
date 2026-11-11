package io.opaa.asset;

import io.opaa.common.SingleValueSetting;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * After how many days without a responsible unit an agent or a document template is locked: the
 * days the system administration set, else the factory default. Read on every call, so a change
 * holds on every instance with the next reading (ADR-0053).
 */
@Component
public class ResponsibilityLockPeriod {

  public static final int FACTORY_DEFAULT_DAYS = 30;
  public static final int MIN_DAYS = 1;
  public static final int MAX_DAYS = 365;

  private final SingleValueSetting setting;

  ResponsibilityLockPeriod(JdbcTemplate jdbc) {
    this.setting = new SingleValueSetting(jdbc, "responsibility_lock_settings", "lock_after_days");
  }

  /** The days in force. */
  public int days() {
    return stored().map(SingleValueSetting.Stored::value).orElse(FACTORY_DEFAULT_DAYS);
  }

  /** The days the administration set, empty while the factory default applies. */
  public Optional<SingleValueSetting.Stored> stored() {
    return setting.stored();
  }

  /**
   * Sets {@code days}; {@code null} returns to the factory default.
   *
   * @throws IllegalArgumentException for a value outside {@value #MIN_DAYS} to {@value #MAX_DAYS}
   */
  public void set(Integer days) {
    if (days == null) {
      setting.clear();
      return;
    }
    if (days < MIN_DAYS || days > MAX_DAYS) {
      throw new IllegalArgumentException("responsibility lock period out of range: " + days);
    }
    setting.set(days);
  }
}
