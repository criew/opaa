package io.opaa.directory.sync;

import io.opaa.common.SingleValueSetting;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The plausibility threshold of the directory sync, installation-wide (ADR-0036, Entscheidung 3): a
 * run that would remove, freeze or lock more than this share of what it touches writes nothing and
 * is left for the system administration to confirm. The percentage the administration set, else the
 * factory default of 30. Read on every run, so a change holds on every instance from the next run
 * on (ADR-0053).
 */
@Component
public class DirectorySyncChangeThreshold {

  public static final int FACTORY_DEFAULT_PERCENT = 30;
  public static final int MIN_PERCENT = 1;
  public static final int MAX_PERCENT = 100;

  private final SingleValueSetting setting;

  DirectorySyncChangeThreshold(JdbcTemplate jdbc) {
    this.setting =
        new SingleValueSetting(jdbc, "directory_sync_settings", "change_threshold_percent");
  }

  /** The threshold in force as a share between 0 (exclusive) and 1 (inclusive). */
  public double fraction() {
    return percent() / 100.0;
  }

  /** The threshold in force in percent. */
  public int percent() {
    return stored().map(SingleValueSetting.Stored::value).orElse(FACTORY_DEFAULT_PERCENT);
  }

  /** The percentage the administration set, empty while the factory default applies. */
  public Optional<SingleValueSetting.Stored> stored() {
    return setting.stored();
  }

  /**
   * Sets {@code percent}; {@code null} returns to the factory default.
   *
   * @throws IllegalArgumentException for a value outside {@value #MIN_PERCENT} to {@value
   *     #MAX_PERCENT}
   */
  void set(Integer percent) {
    if (percent == null) {
      setting.clear();
      return;
    }
    if (percent < MIN_PERCENT || percent > MAX_PERCENT) {
      throw new IllegalArgumentException("change threshold out of range: " + percent);
    }
    setting.set(percent);
  }
}
