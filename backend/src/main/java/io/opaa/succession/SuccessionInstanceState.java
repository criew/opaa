package io.opaa.succession;

import io.opaa.asset.ResponsibilityLockPeriod;
import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The settings of "Ohne Zuständigkeit" in force, for the disclosure: the aging threshold and the
 * days until the lock without a responsible unit, each with the time of its change when the
 * administration set it, else the factory default without one.
 */
@Component
class SuccessionInstanceState implements InstanceStateSource {

  static final String KEY = "succession_settings.aging_threshold_months";
  static final String LOCK_KEY = "responsibility_lock_settings.lock_after_days";

  private final SuccessionAgingThreshold threshold;
  private final ResponsibilityLockPeriod lockPeriod;

  SuccessionInstanceState(SuccessionAgingThreshold threshold, ResponsibilityLockPeriod lockPeriod) {
    this.threshold = threshold;
    this.lockPeriod = lockPeriod;
  }

  @Override
  public Map<String, StoredValue> storedSettings() {
    return Map.of(
        KEY,
        threshold
            .stored()
            .map(stored -> new StoredValue(Integer.toString(stored.value()), stored.updatedAt()))
            .orElseGet(
                () ->
                    new StoredValue(
                        Integer.toString(SuccessionAgingThreshold.FACTORY_DEFAULT_MONTHS), null)),
        LOCK_KEY,
        lockPeriod
            .stored()
            .map(stored -> new StoredValue(Integer.toString(stored.value()), stored.updatedAt()))
            .orElseGet(
                () ->
                    new StoredValue(
                        Integer.toString(ResponsibilityLockPeriod.FACTORY_DEFAULT_DAYS), null)));
  }
}
