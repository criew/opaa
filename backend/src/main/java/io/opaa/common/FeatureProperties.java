package io.opaa.common;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The raw {@code opaa.features.*} block, keyed by {@link Feature#propertyKey()}. Read it through
 * {@link FeatureFlags}, which supplies the default "on" for every flag the block does not name;
 * keys without a {@link Feature} behind them are ignored.
 */
@ConfigurationProperties(prefix = "opaa")
public record FeatureProperties(Map<String, Toggle> features) {

  static final String PREFIX = "opaa.features";

  public FeatureProperties {
    features = features == null ? Map.of() : Map.copyOf(features);
  }

  /** One flag; {@code enabled} defaults to {@code true}. */
  public record Toggle(@DefaultValue("true") boolean enabled) {}
}
