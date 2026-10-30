package io.opaa.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proves on the classes under {@code io.opaa.architecture.fixture.security} that every rule of
 * {@link SecurityInvariants} fires - on a call, a method reference and a connecting constructor -
 * names the offending code unit and is silenced by an allowlist entry only for that unit.
 */
class SecurityInvariantsFixtureTest {

  private static final String FIXTURE = "io.opaa.architecture.fixture.security";

  private static final JavaClasses CLASSES = new ClassFileImporter().importPackages(FIXTURE);

  @Test
  void everyWayToConnectIsReportedByItsCodeUnit() {
    Map<String, String> connecting = SecurityInvariants.connectingUnits(CLASSES);

    assertThat(connecting)
        .containsOnlyKeys(
            FIXTURE + ".RawSender#fetch",
            FIXTURE + ".ReferencedSender#sender",
            FIXTURE + ".RawSocket#open",
            FIXTURE + ".MailNotifier#sender");
    assertThat(connecting.get(FIXTURE + ".ReferencedSender#sender"))
        .startsWith("refers to java.net.http.HttpClient#send");
    assertThat(connecting.get(FIXTURE + ".RawSocket#open")).contains("java.net.Socket#<init>");
    assertThat(connecting.get(FIXTURE + ".MailNotifier#sender"))
        .startsWith("refers to org.springframework.mail.MailSender#send");
    assertThat(
            SecurityInvariants.unlisted(
                connecting,
                Map.of(
                    FIXTURE + ".RawSender#fetch", "reason",
                    FIXTURE + ".ReferencedSender#sender", "reason",
                    FIXTURE + ".RawSocket#open", "reason")))
        .singleElement()
        .asString()
        .startsWith(FIXTURE + ".MailNotifier#sender");
  }

  @Test
  void aDecryptionOutsideTheBlocksIsReportedAlsoAsAReference() {
    Map<String, String> decrypting = SecurityInvariants.decryptingUnits(CLASSES);

    assertThat(decrypting)
        .containsOnlyKeys(
            FIXTURE + ".SecretPeek#reveal", FIXTURE + ".SecretByReference#decryption");
    assertThat(decrypting.get(FIXTURE + ".SecretByReference#decryption"))
        .startsWith("refers to io.opaa.security.SettingsEncryptor#decrypt");
  }

  @Test
  void aHandlerWithoutCheckAndOneThatLetsEveryoneInAreReported() {
    assertThat(SecurityInvariants.handlersWithoutCheck(CLASSES))
        .containsOnlyKeys("GET /fixture/items/{itemId}", "GET /fixture/items/public");
    assertThat(
            SecurityInvariants.unlisted(
                SecurityInvariants.handlersWithoutCheck(CLASSES),
                Map.of(
                    "GET /fixture/items/{itemId}", "reason",
                    "GET /fixture/items/public", "reason")))
        .isEmpty();
  }

  @Test
  void everyPatternCompiledPerCallOrPerObjectIsReported() {
    assertThat(SecurityInvariants.perCallRegex(CLASSES, FIXTURE + ".format"))
        .containsOnlyKeys(
            FIXTURE + ".format.LineReader#lines",
            FIXTURE + ".format.LineReader#isKey",
            FIXTURE + ".format.LineReader#compiler",
            FIXTURE + ".format.LineReader#<init>");
  }

  @Test
  void anEntryWhoseCodeUnitNoLongerBreaksTheRuleIsStale() {
    assertThat(
            SecurityInvariants.stale(
                Set.of(FIXTURE + ".RawSender#fetch", FIXTURE + ".RawSocket#unconnected"),
                SecurityInvariants.connectingUnits(CLASSES).keySet()))
        .containsExactly(FIXTURE + ".RawSocket#unconnected");
  }
}
