package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.compliance.ComplianceManifest.AccessPath;
import io.opaa.compliance.ComplianceManifest.AuditEventEntry;
import io.opaa.compliance.ComplianceManifest.OutputKind;
import io.opaa.compliance.ComplianceManifest.PersonalReference;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The schema check of {@link ComplianceManifestLoader}, against fixtures that break one rule. */
class ComplianceManifestLoaderTest {

  private static final String FIXTURES = "classpath*:compliance-fixtures/";

  @Test
  void readsEveryAreaAndEntryKind() {
    ComplianceManifest manifest = ComplianceManifestLoader.load(FIXTURES + "valid/*.yaml");

    assertThat(manifest.areas())
        .containsEntry("protocol", "Protokoll")
        .containsEntry("roles", "Konten und Rollen");
    assertThat(manifest.data())
        .singleElement()
        .satisfies(
            data -> {
              assertThat(data.area()).isEqualTo("protocol");
              assertThat(data.personalReference()).isEqualTo(PersonalReference.EMPLOYEE);
              assertThat(data.tables().getFirst().personalColumns()).contains("actor_ref");
            });
    assertThat(manifest.nonPersonalTables())
        .extracting(ComplianceManifest.NonPersonalTable::table)
        .containsExactly("audit_retention_settings");
    assertThat(manifest.roles())
        .extracting(ComplianceManifest.RoleEntry::id)
        .containsExactly("USER");
    assertThat(manifest.capabilities())
        .extracting(ComplianceManifest.RoleEntry::id)
        .containsExactly("CREATE_SPACE");
    assertThat(manifest.settings())
        .singleElement()
        .satisfies(
            setting -> {
              assertThat(setting.defaultValue()).isEqualTo("36");
              assertThat(setting.source()).isEqualTo(ComplianceManifest.SettingSource.DATABASE);
            });
    assertThat(manifest.prompts())
        .extracting(ComplianceManifest.PromptEntry::id)
        .containsExactly("answer");
    assertThat(manifest.dataFlows())
        .extracting(ComplianceManifest.DataFlowEntry::id)
        .containsExactly("chat-model");
    assertThat(manifest.aiFunctions())
        .singleElement()
        .satisfies(
            function -> {
              assertThat(function.outputKind()).isEqualTo(OutputKind.TEXT);
              assertThat(function.interactsWithPersons()).isTrue();
              assertThat(function.assessesPersons()).isFalse();
            });
  }

  @Test
  void anEntryInheritsTheDefaultsOfItsFileAndOverridesThemKeyByKey() {
    List<AuditEventEntry> events =
        ComplianceManifestLoader.load(FIXTURES + "valid/*.yaml").auditEvents();

    AuditEventEntry first = events.getFirst();
    assertThat(first.id()).isEqualTo("FIRST_EVENT");
    assertThat(first.storage()).isEqualTo("Tabelle audit_log");
    assertThat(first.accessPaths()).containsExactly(AccessPath.UI, AccessPath.API);
    assertThat(first.retention().settings())
        .containsExactly("db:audit_retention_settings.retention_months");
    assertThat(first.readers()).containsExactly("AUDITOR zur Revision");

    AuditEventEntry second = events.get(1);
    assertThat(second.readers()).containsExactly("Personalvertretung über den Auszug");
    assertThat(second.details()).isEqualTo("before und after tragen die Rolle");
  }

  @Test
  void anEntryWithAMissingOrBlankMandatoryFieldFailsTheWholeManifest() {
    assertThatThrownBy(() -> ComplianceManifestLoader.load(FIXTURES + "missing-field/*.yaml"))
        .isInstanceOfSatisfying(
            ComplianceManifestException.class,
            e ->
                assertThat(e.violations())
                    .containsExactlyInAnyOrder(
                        "protocol: auditEvents 'WITHOUT_TRIGGER': mandatory field 'trigger' is"
                            + " missing or empty",
                        "protocol: auditEvents 'BLANK_STORAGE': mandatory field 'storage' is"
                            + " missing or empty",
                        "protocol: data 'no-retention-rule': mandatory field 'retention.rule' is"
                            + " missing or empty",
                        "protocol: data 'no-retention-rule': mandatory field 'accessPaths' is"
                            + " missing or empty"));
  }

  @Test
  void anUnknownKeyIsRejectedInsteadOfIgnored() {
    assertThatThrownBy(() -> ComplianceManifestLoader.load(FIXTURES + "unknown-key/*.yaml"))
        .isInstanceOfSatisfying(
            ComplianceManifestException.class,
            e ->
                assertThat(e.violations())
                    .hasSize(2)
                    .anySatisfy(v -> assertThat(v).isEqualTo("roles: unknown key 'glossary'"))
                    .anySatisfy(
                        v ->
                            assertThat(v)
                                .startsWith("roles: roles[0]: ")
                                .contains("seesAboutOther")));
  }

  @Test
  void anIdUsedTwiceAcrossAreasIsRejected() {
    assertThatThrownBy(() -> ComplianceManifestLoader.load(FIXTURES + "duplicate/*.yaml"))
        .isInstanceOfSatisfying(
            ComplianceManifestException.class,
            e ->
                assertThat(e.violations())
                    .containsExactlyInAnyOrder(
                        "roles: 'USER' appears more than once",
                        "nonPersonalTables: 'organizations' appears more than once"));
  }

  @Test
  void aReferenceToAnUndescribedSettingOrPromptIsRejected() {
    assertThatThrownBy(() -> ComplianceManifestLoader.load(FIXTURES + "unresolved-setting/*.yaml"))
        .isInstanceOfSatisfying(
            ComplianceManifestException.class,
            e ->
                assertThat(e.violations())
                    .containsExactlyInAnyOrder(
                        "chats: 'chats' names the retention setting"
                            + " 'opaa.chat.auto-cleanup.delete-after-days', which no settings"
                            + " entry describes",
                        "assistant: AI function 'chat-title' names the prompt 'title', which no"
                            + " prompts entry describes"));
  }

  @Test
  void aKeyRepeatedInOneMappingIsRejectedInsteadOfOverwritten() {
    assertThatThrownBy(() -> ComplianceManifestLoader.load(FIXTURES + "duplicate-key/*.yaml"))
        .isInstanceOfSatisfying(
            ComplianceManifestException.class,
            e ->
                assertThat(e.violations())
                    .singleElement()
                    .satisfies(
                        v ->
                            assertThat(v)
                                .startsWith("roles: unreadable YAML")
                                .contains("seesAboutOthers")));
  }

  @Test
  void aPromptNamesExactlyOneLocation() {
    assertThatThrownBy(() -> ComplianceManifestLoader.load(FIXTURES + "prompt-location/*.yaml"))
        .isInstanceOfSatisfying(
            ComplianceManifestException.class,
            e ->
                assertThat(e.violations())
                    .containsExactlyInAnyOrder(
                        "prompts: prompts 'both': rule 'locatedOnce' is not met",
                        "prompts: prompts 'neither': rule 'locatedOnce' is not met"));
  }

  @Test
  void noFileAtTheLocationIsAnErrorNotAnEmptyManifest() {
    assertThatThrownBy(() -> ComplianceManifestLoader.load(FIXTURES + "absent/*.yaml"))
        .isInstanceOf(ComplianceManifestException.class)
        .hasMessageContaining("no manifest file found");
  }
}
