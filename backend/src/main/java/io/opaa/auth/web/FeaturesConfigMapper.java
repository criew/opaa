package io.opaa.auth.web;

import io.opaa.api.dto.FeaturesConfig;
import io.opaa.common.Feature;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maps every {@link Feature} onto the generated {@link FeaturesConfig} by its {@link
 * Feature#jsonName()}, so a new flag needs no change here. A flag without a property in the schema
 * fails the mapping instead of disappearing from the response.
 */
final class FeaturesConfigMapper {

  private static final JsonMapper STRICT =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private FeaturesConfigMapper() {}

  static FeaturesConfig toFeaturesConfig(Map<Feature, Boolean> flags) {
    Map<String, Boolean> byJsonName = new LinkedHashMap<>();
    flags.forEach((feature, enabled) -> byJsonName.put(feature.jsonName(), enabled));
    return STRICT.convertValue(byJsonName, FeaturesConfig.class);
  }
}
