package io.opaa.common;

import java.util.Locale;

/**
 * The installation-wide feature flags (ADR-0044). Each constant is switched by {@code
 * opaa.features.<propertyKey>.enabled}, is on unless the installation sets it to {@code false}, and
 * appears under its {@link #jsonName()} in the {@code features} object of {@code GET
 * /api/v1/auth/config}. A new flag is one constant here, one line in {@code application.yml} and
 * one property in the {@code FeaturesConfig} schema; {@code FeaturesSpecificationTest} holds the
 * spec in step with this enum.
 */
public enum Feature {
  PROMPT_LIBRARIES,
  METADATA;

  /** The key under {@code opaa.features}: the constant in kebab case, e.g. {@code metadata}. */
  public String propertyKey() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /** The full property that switches this flag, e.g. {@code opaa.features.metadata.enabled}. */
  public String enabledProperty() {
    return FeatureProperties.PREFIX + "." + propertyKey() + ".enabled";
  }

  /** The property name in the API: the constant in camel case, e.g. {@code promptLibraries}. */
  public String jsonName() {
    StringBuilder out = new StringBuilder();
    for (String part : name().toLowerCase(Locale.ROOT).split("_")) {
      out.append(out.isEmpty() ? part : Character.toUpperCase(part.charAt(0)) + part.substring(1));
    }
    return out.toString();
  }
}
