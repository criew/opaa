package io.opaa.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.FeaturesConfig;
import io.opaa.common.Feature;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

/** Every flag reaches its own field of the generated DTO, whatever its state. */
class FeaturesConfigMapperTest {

  @Test
  void everyFlagIsMappedOntoItsOwnField() {
    EnumMap<Feature, Boolean> flags = new EnumMap<>(Feature.class);
    flags.put(Feature.PROMPT_LIBRARIES, false);
    flags.put(Feature.METADATA, true);

    FeaturesConfig config = FeaturesConfigMapper.toFeaturesConfig(flags);

    assertThat(config.getPromptLibraries()).isFalse();
    assertThat(config.getMetadata()).isTrue();

    flags.put(Feature.PROMPT_LIBRARIES, true);
    flags.put(Feature.METADATA, false);
    config = FeaturesConfigMapper.toFeaturesConfig(flags);

    assertThat(config.getPromptLibraries()).isTrue();
    assertThat(config.getMetadata()).isFalse();
  }
}
