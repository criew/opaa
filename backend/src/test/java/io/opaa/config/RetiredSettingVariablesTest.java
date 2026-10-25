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
  void theRetiredVariablesAreKnown() {
    assertThat(RetiredSettingVariables.RETIRED)
        .containsOnlyKeys(
            "OPAA_INDEXING_CONFLUENCE_FULL_SYNC_INTERVAL",
            "OPAA_INDEXING_GOOGLE_DRIVE_FULL_SYNC_INTERVAL",
            "OPAA_INDEXING_SHAREPOINT_FULL_SYNC_INTERVAL",
            "OPAA_INDEXING_DROPBOX_FULL_SYNC_INTERVAL",
            "OPAA_LIBRARY_PRIVATE_STORAGE_QUOTA_BYTES",
            "OPAA_CONNECTION_INACTIVITY_THRESHOLD_DAYS",
            "OPAA_CONNECTION_PRIVATE_LIBRARY_DELETION_DAYS",
            "OPAA_CONNECTION_EXPIRED_WARNING_THRESHOLD",
            "opaa.connection.inactivity-threshold-days",
            "opaa.connection.private-library-deletion-days",
            "opaa.connection.expired-connections.warning-threshold");
  }

  /** A property file or a system property naming the old key is found as well. */
  @Test
  void aRetiredPropertyNameIsNamedWithItsNewPlace() {
    MockEnvironment environment =
        new MockEnvironment().withProperty("opaa.connection.inactivity-threshold-days", "120");

    assertThat(RetiredSettingVariables.foundIn(environment))
        .containsExactly(
            "opaa.connection.inactivity-threshold-days is no longer read; set the inactivity"
                + " threshold of the connections under Administration > Anbindungen >"
                + " Einstellungen");
  }

  @Test
  void aRetiredThresholdOfTheConnectionsIsNamedWithItsNewPlace() {
    MockEnvironment environment =
        new MockEnvironment().withProperty("OPAA_CONNECTION_PRIVATE_LIBRARY_DELETION_DAYS", "60");

    assertThat(RetiredSettingVariables.foundIn(environment))
        .containsExactly(
            "OPAA_CONNECTION_PRIVATE_LIBRARY_DELETION_DAYS is no longer read; set the deletion"
                + " period of private libraries under Administration > Anbindungen >"
                + " Einstellungen");
  }
}
