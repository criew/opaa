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
 * names the offending code unit and is silenced by an allowlist entry only for that unit. The
 * whole-row rule fires on a save, a save by method reference and a field change through a delegate,
 * and stays silent where {@code @Version} or {@code @DynamicUpdate} keep the row.
 */
class SecurityInvariantsFixtureTest {

  private static final String FIXTURE = "io.opaa.architecture.fixture.security";

  private static final JavaClasses CLASSES = new ClassFileImporter().importPackages(FIXTURE);

  private static final String ROWS = FIXTURE + ".rows";

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
  void everyStaleWholeRowWriteIsReportedAndAGuardedRowOnlyWhereItCanStillBeStale() {
    JavaClasses rows = new ClassFileImporter().importPackages(ROWS);

    assertThat(
            SecurityInvariants.wholeRowWriters(
                rows, Set.of(ROWS + ".Account", ROWS + ".VersionedToken", ROWS + ".DynamicGroup")))
        .containsOnlyKeys(
            ROWS + ".AccountWriter#rename",
            ROWS + ".AccountWriter#insert",
            ROWS + ".AccountWriter#saver",
            ROWS + ".AccountWriter#deactivateAll")
        .hasEntrySatisfying(
            ROWS + ".AccountWriter#rename",
            call -> assertThat(call).startsWith("calls " + ROWS + ".Account#renameTwice"))
        .hasEntrySatisfying(
            ROWS + ".AccountWriter#saver",
            call -> assertThat(call).startsWith("refers to " + ROWS + ".AccountRepository#save"))
        .hasEntrySatisfying(
            ROWS + ".AccountWriter#deactivateAll",
            call ->
                assertThat(call).startsWith("calls " + ROWS + ".DynamicGroupRepository#saveAll"));
  }

  @Test
  void aVersionedSaveIsFoundThroughAnInterfaceAndALambdaButOnlyWhenReachable() {
    JavaClasses rows = new ClassFileImporter().importPackages(ROWS);

    assertThat(
            SecurityInvariants.versionedSavesReachableFrom(
                rows,
                Set.of(ROWS + ".SignIn#signIn"),
                Set.of(ROWS + ".Account", ROWS + ".VersionedToken", ROWS + ".DynamicGroup")))
        .containsOnlyKeys(ROWS + ".RevokingStep#apply")
        .hasEntrySatisfying(
            ROWS + ".RevokingStep#apply",
            call ->
                assertThat(call).startsWith("calls " + ROWS + ".VersionedTokenRepository#save"));
  }

  @Test
  void aPersistentTypeIsFoundByAFieldNamedLikeSecurityState() {
    assertThat(
            SecurityInvariants.typesWithSecurityStateFields(
                new ClassFileImporter().importPackages(ROWS)))
        .containsExactlyInAnyOrder(
            ROWS + ".Account", ROWS + ".VersionedToken", ROWS + ".DynamicGroup");
  }

  @Test
  void aPromptBuiltPastPromptTextIsReportedByItsMethodEvenNextToOneThatCleans() {
    Map<String, String> prompts = SecurityInvariants.promptBuildingUnits(CLASSES);

    assertThat(prompts)
        .containsOnlyKeys(
            FIXTURE + ".RawPromptWriter#message",
            FIXTURE + ".RawPromptWriter#prompt",
            FIXTURE + ".RawPromptWriter#ask",
            FIXTURE + ".CleanPromptWriter#title");
    assertThat(prompts.get(FIXTURE + ".RawPromptWriter#prompt"))
        .startsWith("calls org.springframework.ai.chat.prompt.Prompt#builder");
    assertThat(prompts.get(FIXTURE + ".RawPromptWriter#ask"))
        .startsWith(
            "calls org.springframework.ai.chat.client.ChatClient$ChatClientRequestSpec#system");
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
