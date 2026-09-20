package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.Capability;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Every capability needs both German wordings (#1813): the label the refusal and the manual use,
 * and the sentence object the administration overview's plain-text line puts between "Alle Konten
 * dürfen" and "anlegen". A value added to {@link Capability} without them would otherwise reach the
 * user interface as the word "null".
 */
class CapabilityLabelTest {

  @ParameterizedTest
  @EnumSource(Capability.class)
  void everyCapabilityHasALabelAndASentenceObject(Capability capability) {
    assertThat(CapabilityService.label(capability))
        .as("the name the refusal and the manual use for %s", capability)
        .isNotBlank();
    assertThat(CapabilityService.creatable(capability))
        .as("what the overview says %s creates", capability)
        .isNotBlank();
  }
}
