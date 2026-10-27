package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The address check of model endpoints: an entry {@code host} releases every port of the host, an
 * entry {@code host:port} only that port, and a stored endpoint passes unchecked.
 */
class ModelAddressPolicyTest {

  @Test
  void anUnlistedInternalHostIsRefusedWithTheVariableOfTheModels() {
    String reason = ModelAddressPolicy.allowing(List.of()).blockedReason("http://10.0.0.5/v1");

    assertThat(reason)
        .contains("OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST")
        .doesNotContain("OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST");
  }

  // regression guard: the refusal does not reveal whether an internal name resolves
  @Test
  void aBlockedAndAnUnresolvableHostAreRefusedWithTheSameSentence() {
    ModelAddressPolicy policy = ModelAddressPolicy.allowing(List.of());

    String blocked = policy.blockedReason("http://10.0.0.5/v1");
    String unknown = policy.blockedReason("http://gibt-es-nicht.invalid/v1");

    assertThat(unknown).isEqualTo(blocked);
    assertThat(blocked).doesNotContain("10.0.0.5", "gesperrt", "DNS");
  }

  @Test
  void anEntryWithoutPortReleasesEveryPortOfTheHost() {
    ModelAddressPolicy policy = ModelAddressPolicy.allowing(List.of("127.0.0.1"));

    assertThat(policy.blockedReason("http://127.0.0.1:11434/v1")).isNull();
    assertThat(policy.blockedReason("http://127.0.0.1:8080/v1")).isNull();
  }

  @Test
  void anEntryWithPortReleasesOnlyThatPort() {
    ModelAddressPolicy policy = ModelAddressPolicy.allowing(List.of("127.0.0.1:11434"));

    assertThat(policy.blockedReason("http://127.0.0.1:11434/v1")).isNull();
    assertThat(policy.blockedReason("http://127.0.0.1:8080/v1"))
        .contains("OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST");
    assertThat(policy.isAllowlisted("http://127.0.0.1:11434/v1")).isTrue();
    assertThat(policy.isAllowlisted("http://127.0.0.1:8080/v1")).isFalse();
  }

  @Test
  void theStoredEndpointPassesUncheckedAnotherDoesNot() {
    ModelAddressPolicy policy = ModelAddressPolicy.allowing(List.of());

    assertThat(policy.blockedReason("http://10.0.0.5/v1/", "http://10.0.0.5/v1")).isNull();
    assertThat(policy.blockedReason("http://10.0.0.5:8080/v1", "http://10.0.0.5/v1")).isNotNull();
    assertThat(policy.blockedReason("http://10.0.0.5/v1", null)).isNotNull();
  }

  @Test
  void theSwitchTurnsTheCheckOff() {
    ModelAddressPolicy off =
        ModelAddressPolicy.of(new ModelTargetValidationProperties(false, List.of()));

    assertThat(off.blockedReason("http://10.0.0.5/v1")).isNull();
    assertThat(new ModelTargetValidationProperties(null, null).enabled()).isTrue();
  }

  @Test
  void anEntryWithAPortOutsideTheRangeRefusesTheStart() {
    assertThatThrownBy(
            () -> new ModelTargetValidationProperties(true, List.of("ollama:99999999999")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ollama:99999999999");
  }
}
