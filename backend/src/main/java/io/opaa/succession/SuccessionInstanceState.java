package io.opaa.succession;

import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The aging threshold of the operational list in force, for the disclosure: the months the
 * administration set with the time of the change, else the factory default without one.
 */
@Component
class SuccessionInstanceState implements InstanceStateSource {

  static final String KEY = "succession_settings.aging_threshold_months";

  private final SuccessionAgingThreshold threshold;

  SuccessionInstanceState(SuccessionAgingThreshold threshold) {
    this.threshold = threshold;
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
                        Integer.toString(SuccessionAgingThreshold.FACTORY_DEFAULT_MONTHS), null)));
  }
}
