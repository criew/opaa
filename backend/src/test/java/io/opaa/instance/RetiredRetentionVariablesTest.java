package io.opaa.instance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * A retired retention setting counts as set under its variable name and under its property name, so
 * an own {@code application-*.yml} or {@code SPRING_APPLICATION_JSON} brakes as well.
 */
class RetiredRetentionVariablesTest {

  @Test
  void bothTheVariableAndThePropertyNameAreFound() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(RetiredRetentionVariables.CHAT_DELETE, " 730 ")
            .withProperty("opaa.audit.incident-scope-retention-years", "3")
            .withProperty("OPAA_LIBRARY_QUOTA_BYTES", "1024");

    assertThat(RetiredRetentionVariables.setIn(environment))
        .containsExactly(
            java.util.Map.entry(RetiredRetentionVariables.CHAT_DELETE, "730"),
            java.util.Map.entry(RetiredRetentionVariables.INCIDENT_SCOPE_YEARS, "3"));
  }

  @Test
  void anEnvironmentWithoutThemReportsNothing() {
    assertThat(RetiredRetentionVariables.setIn(new MockEnvironment())).isEmpty();
  }

  @Test
  void theVariablesNameTheirFormerProperties() {
    assertThat(RetiredRetentionVariables.PROPERTIES)
        .containsEntry(
            RetiredRetentionVariables.LOCKED_ENABLED, "opaa.chat.locked-account-cleanup.enabled")
        .containsEntry(
            RetiredRetentionVariables.PRIVATE_LIBRARY_DELETION,
            "opaa.connection.private-library-deletion-days")
        .hasSize(6);
  }

  /** The switch is read like the former binding; what it could not read pauses. */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"false", "off", "no", "0", "FALSE", "aus"})
  void everyWayTheFormerBindingSwitchedOffPausesAndSoDoesNonsense(String reported) {
    assertThat(RetiredRetentionVariables.switchesOff(reported, true)).isTrue();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"true", "on", "yes", "1"})
  void aSwitchLeftOnDoesNotPause(String reported) {
    assertThat(RetiredRetentionVariables.switchesOff(reported, true)).isFalse();
  }

  /** A period is read like the former binding; what it could not read pauses. */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"730", "+730", "0x2DA", "#2DA", "Unsinn", "1.5"})
  void aLongerOrUnreadablePeriodPauses(String reported) {
    assertThat(RetiredRetentionVariables.keepsLonger(reported, 365)).isTrue();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"365", "+365", "0365", "100"})
  void anEqualOrShorterPeriodDoesNotPause(String reported) {
    assertThat(RetiredRetentionVariables.keepsLonger(reported, 365)).isFalse();
  }
}
