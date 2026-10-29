package io.opaa.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves on the classes under {@code io.opaa.architecture.fixture.security} that every rule of
 * {@link SecurityInvariants} fires, names the offending place and is silenced by an allowlist entry
 * only for that place.
 */
class SecurityInvariantsFixtureTest {

  private static final String FIXTURE = "io.opaa.architecture.fixture.security";

  private static final JavaClasses CLASSES = new ClassFileImporter().importPackages(FIXTURE);

  @Test
  void aDirectSendIsAnUnvalidatedConnection() {
    assertThat(SecurityInvariants.unlisted(SecurityInvariants.connectingClasses(CLASSES), Map.of()))
        .singleElement(STRING)
        .contains(FIXTURE + ".RawSender", "java.net.http.HttpClient#send");
    assertThat(
            SecurityInvariants.unlisted(
                SecurityInvariants.connectingClasses(CLASSES),
                Map.of(FIXTURE + ".RawSender", "reason")))
        .isEmpty();
  }

  @Test
  void aDecryptionOutsideTheBlocksIsReported() {
    assertThat(SecurityInvariants.unlisted(SecurityInvariants.decryptingClasses(CLASSES), Map.of()))
        .singleElement(STRING)
        .contains(FIXTURE + ".SecretPeek", "SettingsEncryptor#decrypt");
  }

  @Test
  void onlyTheHandlerWithoutCheckIsReportedUnderItsOperation() {
    assertThat(SecurityInvariants.handlersWithoutCheck(CLASSES))
        .containsOnlyKeys("GET /fixture/items/{itemId}");
    assertThat(
            SecurityInvariants.unlisted(
                SecurityInvariants.handlersWithoutCheck(CLASSES),
                Map.of("GET /fixture/items/{itemId}", "reason")))
        .isEmpty();
  }

  @Test
  void aPatternCompiledInAMethodAndAStringRegexCallAreReported() {
    assertThat(SecurityInvariants.perCallRegex(CLASSES, FIXTURE + ".format"))
        .containsOnlyKeys(
            FIXTURE + ".format.LineReader#lines", FIXTURE + ".format.LineReader#isKey");
  }

  @Test
  void anEntryWhoseClassNoLongerBreaksTheRuleIsStale() {
    assertThat(
            SecurityInvariants.stale(
                java.util.Set.of(FIXTURE + ".RawSender", FIXTURE + ".Gone"),
                SecurityInvariants.connectingClasses(CLASSES).keySet()))
        .containsExactly(FIXTURE + ".Gone");
  }
}
