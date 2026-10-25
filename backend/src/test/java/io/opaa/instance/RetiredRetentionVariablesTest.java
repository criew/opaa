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
  void theFiveVariablesNameTheirFormerProperties() {
    assertThat(RetiredRetentionVariables.PROPERTIES)
        .containsEntry(
            RetiredRetentionVariables.LOCKED_ENABLED, "opaa.chat.locked-account-cleanup.enabled")
        .hasSize(5);
  }
}
