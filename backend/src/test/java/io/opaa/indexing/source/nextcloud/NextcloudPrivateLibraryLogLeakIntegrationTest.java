package io.opaa.indexing.source.nextcloud;

import static io.opaa.test.ProfileLibraries.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import io.opaa.indexing.document.DocumentIngest;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.PersonalStorageQuota;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The operational logs name no folder, file, attachment or title of a private library (#2167): its
 * runs - new files, a parse failure, an empty file, a mail with an attachment, unchanged files, a
 * removed file - and its erasure log identifiers only. Every application logger is read at DEBUG.
 */
@OpaaIntegrationTest
class NextcloudPrivateLibraryLogLeakIntegrationTest {

  /** Part of every folder, file, attachment and subject name; never part of an identifier. */
  private static final String MARKER = "Geheim";

  private static final String OWNER = "dev-user";
  private static final String LIBRARIES = "/api/v1/libraries";
  private static final String FOLDER = "Geheimordner";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures fixtures;
  @Autowired private PersonalStorageQuota personalQuota;
  @Autowired private DocumentIngestService ingestService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;

  private FakeNextcloudServer nextcloud;
  private UUID profile;
  private UUID library;
  private ListAppender<ILoggingEvent> appender;
  private Logger root;
  private Logger application;
  private Level previousLevel;

  @BeforeEach
  void aPrivateLibraryWithTellingNames() throws Exception {
    nextcloud = new FakeNextcloudServer("");
    nextcloud.put(FOLDER + "/Kuendigung-Geheimakte.txt", "Die Kündigung ergeht wie folgt.");
    nextcloud.put(FOLDER + "/Unterordner-Geheim/Protokoll-Geheim.md", "# Protokoll\n\nText.");
    nextcloud.put(
        FOLDER + "/Defekt-Geheim.pdf",
        "%PDF-1.4 kein echtes PDF".getBytes(StandardCharsets.UTF_8),
        "application/pdf");
    nextcloud.put(FOLDER + "/Leer-Geheim.txt", "");
    nextcloud.put(
        FOLDER + "/Mail-Geheim.eml", mail().getBytes(StandardCharsets.UTF_8), "message/rfc822");
    String body =
        body(
            mockMvc
                .perform(
                    as("dev-admin", post("/api/v1/admin/connection-profiles"))
                        .content(
                            """
                            {"name": "Zugang Nextcloud %s", "sourceType": "NEXTCLOUD",
                             "serverUrl": "%s", "authMethod": "PERSONAL_SECRET",
                             "ownership": "PERSON"}
                            """
                                .formatted(UUID.randomUUID(), nextcloud.baseUrl())))
                .andExpect(status().isCreated()));
    profile = UUID.fromString(JsonPath.read(body, "$.id"));
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + profile);
    mockMvc
        .perform(
            as(OWNER, put("/api/v1/me/connected-accounts/" + profile))
                .content(
                    "{\"username\": \"%s\", \"secret\": \"%s\"}"
                        .formatted(FakeNextcloudServer.LOGIN, FakeNextcloudServer.APP_PASSWORD)))
        .andExpect(status().isOk());

    root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    application = (Logger) LoggerFactory.getLogger("io.opaa");
    previousLevel = application.getLevel();
    application.setLevel(Level.DEBUG);
    appender = new ListAppender<>();
    // the run's download and HTTP threads log while the test reads the list
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    root.addAppender(appender);
  }

  @AfterEach
  void removeOwnRows() {
    root.detachAppender(appender);
    application.setLevel(previousLevel);
    if (library != null) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", library.toString());
      jdbc.update("DELETE FROM asset_grant_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", library);
      fixtures.removeLibraries(library);
    }
    jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
    ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
    jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
    jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    nextcloud.close();
  }

  @Test
  void runsAndErasureOfAPrivateLibraryLogNoNameOfItsContent() throws Exception {
    library = createPrivateLibrary();

    assertThat(run()).isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM documents WHERE library_id = ?", Integer.class, library))
        .as("the run took in the files, the attachment among them")
        .isGreaterThanOrEqualTo(5);
    assertThat(run()).isEqualTo("COMPLETED");
    nextcloud.remove(FOLDER + "/Kuendigung-Geheimakte.txt");
    nextcloud.put(
        FOLDER + "/Unterordner-Geheim/Protokoll-Geheim.md", "# Protokoll\n\nNeue Fassung.");
    nextcloud.put(FOLDER + "/Notiz-Geheim.xyz", "Kein unterstütztes Format.");
    assertThat(run()).isEqualTo("COMPLETED");
    for (String name : List.of("Protokoll-Geheim.md", "Anlage-Geheim.txt", "Mail-Geheim.eml")) {
      mockMvc
          .perform(as(OWNER, get("/api/v1/documents/" + documentNamed(name) + "/content")))
          .andExpect(status().isOk());
    }
    erase();

    assertThat(appender.list).isNotEmpty();
    assertThat(leaking()).isEmpty();
  }

  /**
   * At the owner's exhausted storage quota a file is rejected; the rejection names it only in her
   * own run protocol. An attachment is admitted with its parent's raw bytes and so rejected, if at
   * all, through the same line.
   */
  @Test
  void aRunAtTheOwnersQuotaLogsNoNameOfWhatItRejects() throws Exception {
    library = createPrivateLibrary();
    personalQuota.setQuotaBytes(personalQuota.usageOf(ownerId()) + 10);

    run();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM indexing_run_events e JOIN indexing_jobs j ON j.id = e.job_id"
                    + " WHERE j.library_id = ? AND e.category = 'REJECTED'",
                Integer.class,
                library))
        .as("the quota rejected files")
        .isPositive();
    // an attachment path names its file; the run cannot reach it deterministically, the intake can
    assertThat(
            ingestService.ingest(
                DocumentIngest.text(
                        libraryRepository.findById(library).orElseThrow(),
                        nextcloud.baseUrl() + "/index.php/f/1/0/Anlage-Geheim.txt",
                        "Eine Anlage, die nicht mehr in das Kontingent passt.")
                    .sourceType(NextcloudSourceConnector.TYPE)
                    .build(),
                null))
        .isEqualTo(DocumentIngestResult.PERSONAL_QUOTA_EXCEEDED);
    erase();

    assertThat(leaking()).isEmpty();
  }

  private void erase() throws Exception {
    mockMvc
        .perform(as(OWNER, delete(LIBRARIES + "/" + library)))
        .andExpect(result -> assertThat(result.getResponse().getStatus()).isLessThan(300));
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                jdbc.queryForObject(
                        "SELECT count(*) FROM knowledge_libraries WHERE id = ?",
                        Integer.class,
                        library)
                    == 0);
  }

  private List<String> leaking() {
    List<String> leaking = new ArrayList<>();
    for (ILoggingEvent event : List.copyOf(appender.list)) {
      String text =
          event.getFormattedMessage()
              + (event.getThrowableProxy() == null
                  ? ""
                  : "\n" + ThrowableProxyUtil.asString(event.getThrowableProxy()));
      if (text.contains(MARKER)) {
        leaking.add(event.getLoggerName() + ": " + text.lines().findFirst().orElse(""));
      }
    }
    return leaking;
  }

  private UUID documentNamed(String fileName) {
    return jdbc.queryForObject(
        "SELECT id FROM documents WHERE library_id = ? AND file_name = ?",
        UUID.class,
        library,
        fileName);
  }

  private UUID ownerId() {
    return jdbc.queryForObject(
        "SELECT id FROM users WHERE email = ?", UUID.class, "dev-user@opaa.local");
  }

  private UUID createPrivateLibrary() throws Exception {
    String body =
        body(
            mockMvc
                .perform(
                    as(OWNER, post(LIBRARIES))
                        .content(
                            """
                            {"name": "Meine Cloud %s", "sourceType": "NEXTCLOUD",
                             "privateLibrary": true, "connectionProfileId": "%s",
                             "sourceSettings": {"folders": ["/%s"]}}
                            """
                                .formatted(UUID.randomUUID(), profile, FOLDER)))
                .andExpect(status().isCreated()));
    return UUID.fromString(JsonPath.read(body, "$.id"));
  }

  private String run() throws Exception {
    int before = runs();
    mockMvc
        .perform(as(OWNER, post(LIBRARIES + "/" + library + "/indexing")))
        .andExpect(status().isAccepted());
    String[] state = new String[1];
    await()
        .atMost(Duration.ofSeconds(60))
        .until(
            () -> {
              state[0] =
                  JsonPath.read(
                      body(
                          mockMvc.perform(
                              as(OWNER, get(LIBRARIES + "/" + library + "/indexing/status")))),
                      "$.status");
              return runs() > before && (state[0].equals("COMPLETED") || state[0].equals("FAILED"));
            });
    return state[0];
  }

  private int runs() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM indexing_jobs WHERE library_id = ?", Integer.class, library);
  }

  private static String mail() {
    return """
        From: absender@example.org
        To: empfaenger@example.org
        Subject: Geheimer Betreff
        MIME-Version: 1.0
        Content-Type: multipart/mixed; boundary="GRENZE"

        --GRENZE
        Content-Type: text/plain; charset=utf-8

        Text der Mail.
        --GRENZE
        Content-Type: text/plain; name="Anlage-Geheim.txt"
        Content-Disposition: attachment; filename="Anlage-Geheim.txt"

        Inhalt der Anlage.
        --GRENZE--
        """
        .replace("\n", "\r\n");
  }

  private static String body(ResultActions result) throws Exception {
    return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
  }
}
