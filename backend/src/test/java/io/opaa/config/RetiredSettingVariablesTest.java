package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * A variable that once preset a setting the administration now holds is no longer read; a start
 * that still finds it set names it together with the place where the value lives now.
 */
class RetiredSettingVariablesTest {

  @Test
  void aStartWithoutRetiredVariablesNamesNone() {
    assertThat(RetiredSettingVariables.foundIn(new MockEnvironment())).isEmpty();
  }

  @Test
  void everyRetiredVariableStillSetIsNamedWithItsNewPlace() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("OPAA_INDEXING_SHAREPOINT_FULL_SYNC_INTERVAL", "3d")
            .withProperty("OPAA_LIBRARY_PRIVATE_STORAGE_QUOTA_BYTES", "0")
            .withProperty("OPAA_LIBRARY_QUOTA_BYTES", "1024");

    assertThat(RetiredSettingVariables.foundIn(environment))
        .containsExactly(
            "OPAA_INDEXING_SHAREPOINT_FULL_SYNC_INTERVAL is no longer read; set the full-sync"
                + " rhythm of SharePoint and of OneDrive, which it also covered, under"
                + " Administration > Anbindungen > Einstellungen",
            "OPAA_LIBRARY_PRIVATE_STORAGE_QUOTA_BYTES is no longer read; set the storage quota of"
                + " private libraries under Administration > Anbindungen > Einstellungen");
  }

  /** A value set as variable or as property name is named with the place it lives now. */
  @Test
  void aRetiredAdministrationValueIsNamedUnderVariableOrPropertyName() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("OPAA_EXTERNAL_ACCESS_REMINDER_LEAD_DAYS", "30")
            .withProperty("opaa.directory-sync.change-threshold-fraction", "0.5");

    assertThat(RetiredSettingVariables.foundIn(environment))
        .containsExactly(
            "OPAA_EXTERNAL_ACCESS_REMINDER_LEAD_DAYS is no longer read; set the reminder before a"
                + " release expires under Administration > Anbindungen > Externe KI-Werkzeuge >"
                + " Einstellungen",
            "opaa.directory-sync.change-threshold-fraction is no longer read; set the"
                + " plausibility threshold in percent under Administration > Verzeichnisabgleich;"
                + " until then 30 percent applies, so if it was stricter, restart with"
                + " OPAA_DIRECTORY_SYNC_SCHEDULE_ENABLED=false, set the threshold, then switch the"
                + " schedule on again - a lock ends the person's connected accounts for good");
  }

  /** The environment answers a property name for its variable as well: one message, not two. */
  @Test
  void aValueSetUnderBothNamesIsNamedOnce() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("OPAA_SUCCESSION_AGING_THRESHOLD_MONTHS", "6")
            .withProperty("opaa.succession.aging-threshold-months", "6");

    assertThat(RetiredSettingVariables.foundIn(environment))
        .containsExactly(
            "OPAA_SUCCESSION_AGING_THRESHOLD_MONTHS is no longer read; set the aging threshold"
                + " under Administration > Ohne Zuständigkeit");
  }

  @Test
  void theFourRetiredAdministrationValuesAreKnownUnderBothNames() {
    assertThat(RetiredSettingVariables.RETIRED_ADMINISTRATION_VALUES)
        .containsOnlyKeys(
            "OPAA_EXTERNAL_ACCESS_REMINDER_LEAD_DAYS",
            "opaa.external-access.reminder-lead-days",
            "OPAA_SUCCESSION_AGING_THRESHOLD_MONTHS",
            "opaa.succession.aging-threshold-months",
            "OPAA_DIRECTORY_SYNC_CHANGE_THRESHOLD_FRACTION",
            "opaa.directory-sync.change-threshold-fraction",
            "OPAA_INDEXING_NEXTCLOUD_FULL_DESCENT_INTERVAL",
            "opaa.indexing.nextcloud.full-descent-interval");
  }

  @Test
  void theStartValuesTheInstallationReplacedAreNamedWithItsPlace() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("OPAA_INITIAL_ADMIN_EMAIL", "it@stadt.example")
            .withProperty("OPAA_OIDC_ISSUER_URI", "https://login.stadt.example/realms/opaa")
            .withProperty("OPAA_OPENAI_CHAT_MODEL", "qwen");

    assertThat(RetiredSettingVariables.foundIn(environment))
        .containsExactly(
            "OPAA_INITIAL_ADMIN_EMAIL is no longer read; set the first account in the installation,"
                + " then in the user administration",
            "OPAA_OIDC_ISSUER_URI is no longer read; set the identity provider in the installation,"
                + " then in the Anbieterverwaltung; an internal one needs its hosts in"
                + " OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST",
            "OPAA_OPENAI_CHAT_MODEL is no longer read; set the chat model in the installation, then"
                + " in the model administration");
  }

  @Test
  void theRetiredVariablesAreKnown() {
    assertThat(RetiredSettingVariables.RETIRED)
        .containsOnlyKeys(
            "OPAA_INDEXING_CONFLUENCE_FULL_SYNC_INTERVAL",
            "OPAA_INDEXING_GOOGLE_DRIVE_FULL_SYNC_INTERVAL",
            "OPAA_INDEXING_SHAREPOINT_FULL_SYNC_INTERVAL",
            "OPAA_INDEXING_DROPBOX_FULL_SYNC_INTERVAL",
            "OPAA_LIBRARY_PRIVATE_STORAGE_QUOTA_BYTES",
            "OPAA_INITIAL_ADMIN_EMAIL",
            "OPAA_INITIAL_ADMIN_PASSWORD",
            "OPAA_OIDC_ISSUER_URI",
            "OPAA_OIDC_CLIENT_ID",
            "OPAA_OIDC_JWK_SET_URI",
            "OPAA_OIDC_AUTHORITY",
            "OPAA_OIDC_BOOTSTRAP",
            "OPAA_OPENAI_CHAT_BASE_URL",
            "OPAA_OPENAI_CHAT_API_KEY",
            "OPAA_OPENAI_CHAT_MODEL",
            "OPAA_OPENAI_CHAT_TEMPERATURE",
            "OPAA_OPENAI_CHAT_MAX_TOKENS",
            "OPAA_AI_CHAT_PROVIDER",
            "OPAA_OLLAMA_CHAT_MODEL",
            "OPAA_OLLAMA_BASE_URL",
            "OPAA_OLLAMA_EMBEDDING_MODEL",
            "OPAA_AI_EMBEDDING_PROVIDER",
            "OPAA_OPENAI_EMBEDDING_BASE_URL",
            "OPAA_OPENAI_EMBEDDING_API_KEY",
            "OPAA_OPENAI_EMBEDDING_MODEL",
            "OPAA_OPENAI_EMBEDDING_MAX_INPUT_TOKENS",
            "OPAA_PGVECTOR_DIMENSIONS");
  }

  @Test
  void aRetiredRetentionPeriodStillSetIsNamedWithThePrivacySettings() {
    MockEnvironment environment =
        new MockEnvironment().withProperty("OPAA_AUDIT_INCIDENT_SCOPE_RETENTION_YEARS", "3");

    assertThat(RetiredSettingVariables.foundIn(environment))
        .containsExactly(
            "OPAA_AUDIT_INCIDENT_SCOPE_RETENTION_YEARS is no longer read; set the retention of"
                + " the anlassbezogene Klaerungen under Administration > Datenschutz; it is never"
                + " taken over, it only brakes: while it keeps longer than the factory default and"
                + " no value is set there, the deletion it governs pauses, otherwise deletion"
                + " follows the factory default once its period is reached");
  }

  @Test
  void theFiveRetiredRetentionVariablesAreKnown() {
    assertThat(RetiredSettingVariables.RETIRED_RETENTION)
        .containsOnlyKeys(
            "OPAA_CHAT_AUTO_CLEANUP_ARCHIVE_AFTER_DAYS",
            "OPAA_CHAT_AUTO_CLEANUP_DELETE_AFTER_DAYS",
            "OPAA_CHAT_LOCKED_ACCOUNT_CLEANUP_ENABLED",
            "OPAA_CHAT_LOCKED_ACCOUNT_CLEANUP_DELETE_AFTER_DAYS",
            "OPAA_AUDIT_INCIDENT_SCOPE_RETENTION_YEARS");
  }
}
