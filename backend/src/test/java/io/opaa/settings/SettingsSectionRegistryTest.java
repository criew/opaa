package io.opaa.settings;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.MigrationSchema;
import io.opaa.test.OpaaIntegrationTest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Every setting of the installation reaches the settings document (ADR-0060, Entscheidung 12): a
 * table of settings - named {@code *_settings}, or listed in {@link #OTHER_SETTINGS_TABLES} - is
 * claimed by a {@link SettingsSection} bean, or stands in {@link #NOT_TRANSFERRED} with the reason
 * it stays out. A new settings table without either fails here.
 */
@OpaaIntegrationTest
class SettingsSectionRegistryTest {

  /** Settings tables whose name does not end in {@code _settings}. */
  static final Set<String> OTHER_SETTINGS_TABLES = Set.of("mail_templates");

  /**
   * Tables of the administration that are no part of the document, with the reason: they connect
   * this installation to its own services, or decide who signs in and with which role.
   */
  static final Map<String, String> NOT_TRANSFERRED =
      Map.of(
          "llm_models",
              "chat models: the address receives every chat; no stable key, the access key of this"
                  + " installation never follows a new address",
          "embedding_model",
              "the embedding model: its vectors fill this installation's index; set by the"
                  + " installation, never changed by an import (ADR-0060, Entscheidung 10)",
          "rerank_model",
              "the rerank model: address and key of this installation, the key never follows a"
                  + " new address",
          "transcription_model",
              "the transcription model: the address receives speech input, the key never follows"
                  + " a new address",
          "oidc_providers",
              "identity providers: the sign-in procedure and the mapping of claims to system"
                  + " roles; an import could lock the administration out or widen who is admin",
          "directory_connectors",
              "directory access with the secret of a service account, bound to an identity"
                  + " provider that is not transferred either",
          "connection_profiles",
              "connection profiles: app registration and secret of this installation; a changed"
                  + " address, tenant or scope discards every connected account");

  @Autowired private SettingsTransferService transfer;

  @Test
  void everySettingsTableIsClaimedByASectionOrExcludedWithAReason() {
    Set<String> claimed = new HashSet<>();
    transfer.sections().forEach(section -> claimed.addAll(section.tables()));
    Set<String> settingsTables = new TreeSet<>(OTHER_SETTINGS_TABLES);
    MigrationSchema.read().keySet().stream()
        .filter(table -> table.endsWith("_settings"))
        .forEach(settingsTables::add);

    Set<String> unclaimed = new TreeSet<>(settingsTables);
    unclaimed.removeAll(claimed);
    unclaimed.removeAll(NOT_TRANSFERRED.keySet());

    assertThat(unclaimed)
        .as(
            "a settings table no SettingsSection claims - add a section in the module that owns"
                + " the setting, or list the table in NOT_TRANSFERRED with the reason")
        .isEmpty();
  }

  @Test
  void everyClaimedOrExcludedTableExistsAndNoTableIsBoth() {
    Set<String> tables = MigrationSchema.read().keySet();
    Set<String> claimed = new HashSet<>();
    transfer.sections().forEach(section -> claimed.addAll(section.tables()));

    assertThat(tables).containsAll(claimed).containsAll(NOT_TRANSFERRED.keySet());
    assertThat(claimed).doesNotContainAnyElementsOf(NOT_TRANSFERRED.keySet());
  }

  /** The preview names every key in German and marks only keys the section exports. */
  @Test
  void everyExportedKeyHasAGermanLabelAndEveryMarkedKeyIsExported() {
    io.opaa.auth.CurrentUser admin =
        io.opaa.auth.CurrentUser.of(
            java.util.UUID.randomUUID(),
            io.opaa.organization.Organization.DEFAULT_ID,
            io.opaa.api.types.SystemRole.SYSTEM_ADMIN,
            "Systemverwaltung");
    for (SettingsSection section : transfer.sections()) {
      Set<String> exported = section.export(admin).keySet();
      assertThat(section.keyLabels().keySet()).as(section.key()).isEqualTo(exported);
      assertThat(exported).as(section.key()).containsAll(section.securityRelevantKeys());
    }
  }

  @Test
  void everySectionHasALowerCamelCaseKeyAndAGermanLabel() {
    assertThat(transfer.sections())
        .isNotEmpty()
        .allSatisfy(
            section -> {
              assertThat(section.key()).matches("[a-z][A-Za-z]*");
              assertThat(section.label()).isNotBlank();
              assertThat(section.tables()).isNotEmpty();
            });
  }
}
