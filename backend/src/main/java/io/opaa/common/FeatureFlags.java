package io.opaa.common;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The one place to ask whether an installation-wide feature is switched on (ADR-0044). Every flag
 * is on unless {@code opaa.features.<key>.enabled} is {@code false}; the answer is fixed at
 * start-up. A switched-off feature hides its functions and ignores existing data, it never deletes
 * anything. Use this runtime check where removing a bean would be wrong; for leaf beans see {@link
 * ConditionalOnFeature}. Both read a flag through {@link FeatureResolution}.
 */
@Component
public class FeatureFlags {

  private static final Logger log = LoggerFactory.getLogger(FeatureFlags.class);

  private final Map<Feature, Boolean> flags;

  public FeatureFlags(Environment environment) {
    EnumMap<Feature, Boolean> resolved = new EnumMap<>(Feature.class);
    for (Feature feature : Feature.values()) {
      resolved.put(feature, FeatureResolution.isEnabled(environment, feature));
    }
    this.flags = Collections.unmodifiableMap(resolved);
    resolved.forEach(
        (feature, enabled) -> {
          if (!enabled) {
            log.info(
                "Feature '{}' is switched off ({})",
                feature.propertyKey(),
                feature.enabledProperty());
          }
        });
  }

  public boolean isEnabled(Feature feature) {
    return flags.get(feature);
  }

  /** Every flag with its state, in the declaration order of {@link Feature}. */
  public Map<Feature, Boolean> asMap() {
    return flags;
  }
}
