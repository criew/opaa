package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.SelfDisclosureSubject;
import io.opaa.test.CapturingSelfDisclosureSink;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import io.opaa.test.SelfDisclosureTestData;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The category {@code library-settings} against Postgres: releases for external AI tools the person
 * set, her private libraries with the source address stripped of user info and query, and nothing
 * of another person's libraries or of the stored credentials and webhook secret.
 */
@OpaaIntegrationTest
class LibrarySettingsSelfDisclosureExporterIntegrationTest {

  @Autowired private LibrarySettingsSelfDisclosureExporter exporter;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnOrganizationFixtures organizations;

  private SelfDisclosureTestData data;
  private UUID person;
  private UUID other;

  @BeforeEach
  void setUp() {
    data = SelfDisclosureTestData.newOrganization(jdbc, "sd-library-settings-org");
    person = data.user("petra-person");
    other = data.user("otto-fremd");
  }

  @AfterEach
  void tearDown() {
    data.removeAll(organizations);
  }

  @Test
  void exportsOwnReleasesAndPrivateLibrariesWithoutSecretsOrForeignLibraries() throws Exception {
    UUID shared = data.library("Gemeinsame Bibliothek", other, false, "UPLOAD");
    jdbc.update(
        "UPDATE knowledge_libraries SET external_access_state = 'ACTIVE',"
            + " external_access_set_at = now(), external_access_expires_at = now() + interval"
            + " '30 days', external_access_set_by_user_id = ? WHERE id = ?",
        person,
        shared);
    UUID foreignRelease = data.library("SD-FOREIGN Freigabe", other, false, "UPLOAD");
    jdbc.update(
        "UPDATE knowledge_libraries SET external_access_state = 'ACTIVE',"
            + " external_access_set_at = now(), external_access_expires_at = now() + interval"
            + " '30 days', external_access_set_by_user_id = ? WHERE id = ?",
        other,
        foreignRelease);
    UUID mine = data.library("Meine Cloud", person, true, "NEXTCLOUD");
    jdbc.update(
        "UPDATE knowledge_libraries SET source_url = ?, source_path = '/Akten/2026',"
            + " source_credentials = 'SD-SECRET-CRED', source_webhook_secret = 'SD-SECRET-HOOK'"
            + " WHERE id = ?",
        "https://petra:SD-PASSWORD@cloud.example.org:8443/remote.php/dav?token=SD-TOKEN#frag",
        mine);
    UUID foreignPrivate = data.library("SD-FOREIGN Privat", other, true, "NEXTCLOUD");
    jdbc.update(
        "UPDATE knowledge_libraries SET source_url = 'https://otto.example/x' WHERE id = ?",
        foreignPrivate);

    CapturingSelfDisclosureSink sink =
        new CapturingSelfDisclosureSink(LibrarySettingsSelfDisclosureExporter.CATEGORY);
    exporter.export(new SelfDisclosureSubject(person, data.organizationId()), sink);

    assertThat(sink.records("externalAccess"))
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record).containsEntry("library", "Gemeinsame Bibliothek");
              assertThat(record).containsEntry("state", "aktiv");
              assertThat(record.get("setAt")).isNotNull();
            });
    assertThat(sink.records("privateLibraries"))
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record).containsEntry("library", "Meine Cloud");
              assertThat(record).containsEntry("sourceType", "Nextcloud");
              assertThat(record)
                  .containsEntry("sourceUrl", "https://cloud.example.org:8443/remote.php/dav");
              assertThat(record).containsEntry("sourcePath", "/Akten/2026");
            });
    assertThat(sink.text())
        .doesNotContain("SD-SECRET")
        .doesNotContain("SD-PASSWORD")
        .doesNotContain("SD-TOKEN")
        .doesNotContain("SD-FOREIGN")
        .doesNotContain("otto")
        .doesNotContain(other.toString());
  }

  @Test
  void anAddressThatDoesNotParseLosesUserInfoAndQueryAsText() {
    assertThat(
            LibrarySettingsSelfDisclosureExporter.withoutSecrets(
                "smb://user:pw@server/share with space?x=1"))
        .isEqualTo("smb://server/share with space");
    assertThat(LibrarySettingsSelfDisclosureExporter.withoutSecrets("\\\\server\\share"))
        .isEqualTo("\\\\server\\share");
  }
}
