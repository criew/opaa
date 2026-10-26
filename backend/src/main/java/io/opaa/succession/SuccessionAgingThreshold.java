package io.opaa.succession;

import io.opaa.common.SingleValueSetting;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * From which age an entry of the operational list is highlighted (ADR-0036, Entscheidung 6): the
 * months the system administration set, else the factory default of twelve, taken from the maximum
 * validity of the Vollmacht. It highlights and never triggers anything. Read on every call, so a
 * change holds on every instance with the next reading (ADR-0053).
 */
@Component
public class SuccessionAgingThreshold {

  public static final int FACTORY_DEFAULT_MONTHS = 12;
  public static final int MIN_MONTHS = 1;
  public static final int MAX_MONTHS = 120;

  private final SingleValueSetting setting;

  SuccessionAgingThreshold(JdbcTemplate jdbc) {
    this.setting = new SingleValueSetting(jdbc, "succession_settings", "aging_threshold_months");
  }

  /** The months in force. */
  public int months() {
    return stored().map(SingleValueSetting.Stored::value).orElse(FACTORY_DEFAULT_MONTHS);
  }

  /** The months the administration set, empty while the factory default applies. */
  public Optional<SingleValueSetting.Stored> stored() {
    return setting.stored();
  }

  /**
   * Sets {@code months}; {@code null} returns to the factory default.
   *
   * @throws IllegalArgumentException for a value outside {@value #MIN_MONTHS} to {@value
   *     #MAX_MONTHS}
   */
  void set(Integer months) {
    if (months == null) {
      setting.clear();
      return;
    }
    if (months < MIN_MONTHS || months > MAX_MONTHS) {
      throw new IllegalArgumentException("aging threshold out of range: " + months);
    }
    setting.set(months);
  }
}
