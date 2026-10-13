package io.opaa.common;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * The one rule by which a feature flag is read, shared by {@link FeatureFlags} and {@link
 * ConditionalOnFeature}: {@code opaa.features.<key>.enabled} bound with Spring's relaxed binding
 * (so {@code promptLibraries} and {@code prompt_libraries} mean {@code prompt-libraries}), and on
 * while the property is absent. A value that is no boolean stops the start.
 */
final class FeatureResolution {

  private FeatureResolution() {}

  static boolean isEnabled(Environment environment, Feature feature) {
    return Binder.get(environment).bind(feature.enabledProperty(), Boolean.class).orElse(true);
  }
}
