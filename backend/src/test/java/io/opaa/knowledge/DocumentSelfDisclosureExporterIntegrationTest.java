package io.opaa.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.SelfDisclosureSubject;
import io.opaa.test.CapturingSelfDisclosureSink;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import io.opaa.test.SelfDisclosureTestData;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The category {@code documents} against Postgres: a directory of the person's uploads and of the
 * documents of her private libraries, path and folder only there and never the locator of a stored
 * original, and nothing of another person's documents or the technical columns.
 */
@OpaaIntegrationTest
class DocumentSelfDisclosureExporterIntegrationTest {

  @Autowired private DocumentSelfDisclosureExporter exporter;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnOrganizationFixtures organizations;

  private SelfDisclosureTestData data;
  private UUID person;
  private UUID other;

  @BeforeEach
  void setUp() {
    data = SelfDisclosureTestData.newOrganization(jdbc, "sd-documents-org");
    person = data.user("petra-person");
    other = data.user("otto-fremd");
  }

  @AfterEach
  void tearDown() {
    data.removeAll(organizations);
  }

  @Test
  void listsOwnUploadsAndPrivateLibraryDocumentsOnlyAsDirectory() throws Exception {
    UUID shared = data.library("Gemeinsame Ablage", other, false, "UPLOAD");
    UUID ownUpload =
        data.document(
            shared, "antrag.pdf", "SD-LOCATOR-shared", "SD-FOLDER-shared", "UPLOAD", person, "SD1");
    data.document(
        shared, "SD-FOREIGN-otto.pdf", "SD-LOCATOR-foreign", null, "UPLOAD", other, "SD2");
    data.document(shared, "SD-FOREIGN-connector.pdf", "x", null, "UPLOAD", null, "SD3");
    UUID cloud = data.library("Meine Cloud", person, true, "NEXTCLOUD");
    data.document(
        cloud,
        "vermerk.docx",
        "/Akten/2026/vermerk.docx",
        "Akten › 2026",
        "NEXTCLOUD",
        null,
        "SD4");
    UUID privateUploads = data.library("Meine Uploads", person, true, "UPLOAD");
    data.document(privateUploads, "notiz.txt", "SD-LOCATOR-private", null, "UPLOAD", person, "SD5");
    UUID foreignPrivate = data.library("SD-FOREIGN Privat", other, true, "NEXTCLOUD");
    data.document(foreignPrivate, "SD-FOREIGN-private.pdf", "/x", null, "NEXTCLOUD", null, "SD6");

    CapturingSelfDisclosureSink sink =
        new CapturingSelfDisclosureSink(DocumentSelfDisclosureExporter.CATEGORY);
    exporter.export(new SelfDisclosureSubject(person, data.organizationId()), sink);

    assertThat(sink.records("uploads"))
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record).containsEntry("fileName", "antrag.pdf");
              assertThat(record).containsEntry("library", "Gemeinsame Ablage");
              assertThat(record.get("createdAt")).isNotNull();
            });
    List<Map<String, Object>> privateDocuments = sink.records("privateLibraries");
    assertThat(privateDocuments)
        .extracting(r -> r.get("fileName"))
        .containsExactlyInAnyOrder("vermerk.docx", "notiz.txt");
    assertThat(privateDocuments)
        .filteredOn(r -> "vermerk.docx".equals(r.get("fileName")))
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record).containsEntry("path", "/Akten/2026/vermerk.docx");
              assertThat(record).containsEntry("folder", "Akten › 2026");
              assertThat(record).containsEntry("library", "Meine Cloud");
              assertThat(record).containsEntry("uploadedByYou", false);
            });
    assertThat(privateDocuments)
        .filteredOn(r -> "notiz.txt".equals(r.get("fileName")))
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record.get("path")).isNull();
              assertThat(record).containsEntry("uploadedByYou", true);
            });
    assertThat(sink.text())
        .doesNotContain("SD-LOCATOR")
        .doesNotContain("SD-FOLDER")
        .doesNotContain("SD-FOREIGN")
        .doesNotContain("-checksum")
        .doesNotContain("-container-key")
        .doesNotContain("-entry")
        .doesNotContain("-prefix-title")
        .doesNotContain("-error")
        .doesNotContain(ownUpload.toString())
        .doesNotContain("otto")
        .doesNotContain(other.toString());
  }
}
