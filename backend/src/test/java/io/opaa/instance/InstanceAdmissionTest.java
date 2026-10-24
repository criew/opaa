package io.opaa.instance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.instance.InstanceAdmission.LiveInstance;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The admission of a starting instance next to the live ones (ADR-0053, Entscheidung 4). */
class InstanceAdmissionTest {

  private static final byte[] KEY = new byte[32];
  private static final InstanceVersion VERSION = new InstanceVersion("1.4.0", "1.3.0");
  private static final UUID LIVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");

  private static final List<FingerprintValue> OWN =
      List.of(
          new FingerprintValue("opaa.auth.local.jwt-secret", "own-secret", true),
          new FingerprintValue("spring.ai.vectorstore.pgvector.dimensions", "1536", false),
          new FingerprintValue("opaa.public-base-url", "https://user:pw@opaa.example/x", false));

  @Test
  void admitsAnInstanceThatHoldsTheSameValues() {
    assertThatCode(() -> check(live(VERSION, OWN))).doesNotThrowAnyException();
  }

  @Test
  void namesEveryDifferingKeyButNeverTheValueOfASecret() {
    List<FingerprintValue> other =
        List.of(
            new FingerprintValue("opaa.auth.local.jwt-secret", "other-secret", true),
            new FingerprintValue("spring.ai.vectorstore.pgvector.dimensions", "1024", false),
            new FingerprintValue("opaa.public-base-url", "https://elsewhere.example", false));

    assertThatThrownBy(() -> check(live(VERSION, other)))
        .isInstanceOf(InstanceStartRefusedException.class)
        .hasMessageContaining(LIVE.toString())
        .hasMessageContaining("opaa.auth.local.jwt-secret")
        .hasMessageContaining("spring.ai.vectorstore.pgvector.dimensions (this instance: 1536)")
        .hasMessageContaining("opaa.public-base-url (this instance: https://opaa.example/x)")
        .message()
        .doesNotContain("own-secret")
        .doesNotContain("other-secret")
        .doesNotContain("user:pw");
  }

  @Test
  void comparesOnlyTheKeysBothVersionsKnow() {
    List<FingerprintValue> olderKeys =
        List.of(new FingerprintValue("opaa.auth.local.jwt-secret", "own-secret", true));

    assertThatCode(() -> check(live(new InstanceVersion("1.3.0", "1.2.0"), olderKeys)))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesAVersionOutsideTheWindow() {
    assertThatThrownBy(() -> check(live(new InstanceVersion("1.2.0", "1.1.0"), OWN)))
        .isInstanceOf(InstanceStartRefusedException.class)
        .hasMessageContaining("version 1.4.0 may not run next to version 1.2.0")
        .hasMessageContaining("version 1.4.0 runs next to versions from 1.3.0");
  }

  private static LiveInstance live(InstanceVersion version, List<FingerprintValue> values) {
    return new LiveInstance(LIVE, "pod-a", version, ConfigurationFingerprint.of(values, KEY));
  }

  private static void check(LiveInstance live) {
    InstanceAdmission.check(
        UUID.randomUUID(), VERSION, ConfigurationFingerprint.of(OWN, KEY), OWN, List.of(live));
  }

  @Test
  void anUnsetValueDiffersFromAnEmptyOne() {
    ConfigurationFingerprint unset =
        ConfigurationFingerprint.of(List.of(new FingerprintValue("k", null, false)), KEY);
    ConfigurationFingerprint empty =
        ConfigurationFingerprint.of(List.of(new FingerprintValue("k", "", false)), KEY);

    assertThat(unset.differingKeys(empty)).containsExactly("k");
  }
}
