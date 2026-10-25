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

  @Test
  void theFiveRetiredVariablesAreKnown() {
    assertThat(RetiredSettingVariables.RETIRED)
        .containsOnlyKeys(
            "OPAA_INDEXING_CONFLUENCE_FULL_SYNC_INTERVAL",
            "OPAA_INDEXING_GOOGLE_DRIVE_FULL_SYNC_INTERVAL",
            "OPAA_INDEXING_SHAREPOINT_FULL_SYNC_INTERVAL",
            "OPAA_INDEXING_DROPBOX_FULL_SYNC_INTERVAL",
            "OPAA_LIBRARY_PRIVATE_STORAGE_QUOTA_BYTES");
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
