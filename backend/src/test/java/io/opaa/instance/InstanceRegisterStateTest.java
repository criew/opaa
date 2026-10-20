package io.opaa.instance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The keys the disclosure reports as differing between running instances. */
class InstanceRegisterStateTest {

  private static final byte[] KEY = new byte[32];

  @Test
  void reportsAKeyWhoseDigestsDifferAndOnlyThatOne() {
    ConfigurationFingerprint first =
        ConfigurationFingerprint.of(
            List.of(new FingerprintValue("a", "1", false), new FingerprintValue("b", "x", false)),
            KEY);
    ConfigurationFingerprint second =
        ConfigurationFingerprint.of(
            List.of(new FingerprintValue("a", "2", false), new FingerprintValue("b", "x", false)),
            KEY);

    assertThat(InstanceRegisterState.differingKeys(List.of(first, second))).containsExactly("a");
  }

  @Test
  void aKeyOnlyOneInstanceKnowsIsNoDifference() {
    ConfigurationFingerprint newer =
        ConfigurationFingerprint.of(List.of(new FingerprintValue("new", "1", false)), KEY);
    ConfigurationFingerprint older = ConfigurationFingerprint.of(List.of(), KEY);

    assertThat(InstanceRegisterState.differingKeys(List.of(newer, older))).isEmpty();
  }
}
