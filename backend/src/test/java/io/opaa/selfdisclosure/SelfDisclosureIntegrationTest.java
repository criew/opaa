package io.opaa.selfdisclosure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.NotificationType;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.NotFoundException;
import io.opaa.mail.MailSettingsService;
import io.opaa.notification.NotificationService;
import io.opaa.test.LocalMailbox;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.TwoInstanceRace;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The self-disclosure end to end against Postgres, with the reference exporter {@code
 * notifications} and the category {@code self-disclosure}: ticket, streamed ZIP, daily limit and
 * starts, emergency switch, bound per instance, aborted delivery, audit events, notification and
 * mail. Only the rows of the two development accounts are touched and cleaned.
 */
@OpaaIntegrationTest
class SelfDisclosureIntegrationTest {

  private static final String STATUS = "/api/v1/me/self-disclosure";
  private static final String TICKETS = "/api/v1/me/self-disclosure/tickets";
  private static final String DOWNLOAD = "/api/v1/me/self-disclosure/download";
  private static final String SETTINGS = "/api/v1/admin/self-disclosure/settings";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository users;
  @Autowired private NotificationService notifications;
  @Autowired private SelfDisclosureService service;
  @Autowired private SelfDisclosureRunRegistry registry;
  @Autowired private SelfDisclosureUsageRepository usage;
  @Autowired private MailSettingsService mailSettings;
  @Autowired private DataSource dataSource;

  @LocalServerPort private int port;

  private UUID person;
  private UUID admin;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc.perform(get(STATUS).with(dev("dev-admin"))).andExpect(status().isOk());
    mockMvc.perform(get(STATUS).with(dev("dev-user"))).andExpect(status().isOk());
    admin = idOf("dev-admin");
    person = idOf("dev-user");
    cleanUp();
  }

  @AfterEach
  void cleanUp() {
    for (UUID id : List.of(person, admin)) {
      jdbc.update("DELETE FROM self_disclosure_usage WHERE account_id = ?", id);
      jdbc.update(
          "DELETE FROM notifications WHERE recipient_user_id = ? AND (type = ? OR title LIKE ?)",
          id,
          NotificationType.SELF_DISCLOSURE_STARTED.name(),
          "sd-test %");
    }
    LocalMailbox.awaitOutboxDrained(jdbc);
  }

  @Test
  void aCompleteExportDeliversTheZipCountsItAndRecordsStartAndEnd() throws Exception {
    notifications.notify(
        users.findById(person).orElseThrow().getOrganizationId(),
        person,
        NotificationType.GROUP_MEMBER_ADDED,
        null,
        null,
        "sd-test <b>Gruppe</b> & mehr",
        "Text der Benachrichtigung");

    MvcResult download = download(ticketOf("dev-user"));

    assertThat(download.getResponse().getContentType()).isEqualTo("application/zip");
    assertThat(download.getResponse().getHeader("Content-Disposition"))
        .matches("attachment; filename=\"opaa-selbstauskunft-\\d{4}-\\d{2}-\\d{2}\\.zip\"");
    assertThat(download.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    byte[] zip = download.getResponse().getContentAsByteArray();
    Map<String, String> entries = entries(zip);
    assertThat(new ArrayList<>(entries.keySet()))
        .containsExactly(
            "notifications.json", "self-disclosure.json", "manifest.json", "index.html");
    assertThat(entries.get("notifications.json"))
        .contains("\"category\":\"notifications\"")
        .contains("sd-test <b>Gruppe</b> & mehr")
        .contains("Selbstauskunft gestartet");
    assertThat(entries.get("self-disclosure.json")).contains("\"starts\":1");
    assertThat(entries.get("manifest.json")).contains("\"opaaVersion\"").contains("\"records\"");
    assertThat(entries.get("index.html"))
        .contains("<html lang=\"de\">")
        .contains(SelfDisclosureOverviewRenderer.NOTICE)
        .contains("sd-test &lt;b&gt;Gruppe&lt;/b&gt; &amp; mehr")
        .contains("Diese Version enthält noch nicht alle Kategorien")
        .doesNotContain("<script");
    assertThat(entries.keySet()).noneMatch(name -> name.contains("sig") || name.contains("sha"));

    mockMvc
        .perform(get(STATUS).with(dev("dev-user")))
        .andExpect(jsonPath("$.completedToday").value(1))
        .andExpect(jsonPath("$.startsToday").value(1))
        .andExpect(jsonPath("$.remainingExports").value(0))
        .andExpect(jsonPath("$.blocked").value("DAILY_LIMIT"))
        .andExpect(jsonPath("$.lastResult").value("COMPLETED"))
        .andExpect(jsonPath("$.lastCompletedAt").isString())
        .andExpect(jsonPath("$.nextPossibleAt").isString());

    List<Map<String, Object>> events = eventsOf(person);
    assertThat(events)
        .extracting(e -> e.get("event_type"))
        .containsExactly("SELF_DISCLOSURE_EXPORT_STARTED", "SELF_DISCLOSURE_EXPORT_FINISHED");
    assertThat(events.get(0).get("correlation_ref"))
        .isEqualTo(events.get(1).get("correlation_ref"));
    assertThat(events.get(0).get("object_type")).isEqualTo("USER_ACCOUNT");
    assertThat(events.get(0).get("actor_ref")).isEqualTo(events.get(0).get("subject_ref"));
    String after = String.valueOf(events.get(1).get("after"));
    assertThat(after)
        .contains("\"result\":\"COMPLETED\"", "notifications")
        .contains(sha256(zip))
        .contains(String.valueOf(zip.length))
        .doesNotContain("sd-test")
        .doesNotContain("Gruppe");
    assertThat(events.get(1).get("outcome")).isEqualTo("SUCCESS");
  }

  @Test
  void theDailyLimitFollowsTheSettingAndTheSwitchActsWithoutRestartAndIsRecorded()
      throws Exception {
    download(ticketOf("dev-user"));
    mockMvc
        .perform(post(TICKETS).with(dev("dev-user")))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("SELF_DISCLOSURE_DAILY_LIMIT"))
        .andExpect(header().exists("Retry-After"));

    mockMvc
        .perform(
            put(SETTINGS)
                .with(dev("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dailyLimit\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dailyLimit").value(2));
    download(ticketOf("dev-user"));

    mockMvc
        .perform(
            put(SETTINGS)
                .with(dev("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(post(TICKETS).with(dev("dev-admin")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SELF_DISCLOSURE_DISABLED"))
        .andExpect(jsonPath("$.error").value(SelfDisclosureService.DISABLED_MESSAGE));
    mockMvc
        .perform(get(STATUS).with(dev("dev-admin")))
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.blocked").value("DISABLED"));

    List<String> changes =
        jdbc.queryForList(
            "SELECT after FROM audit_log WHERE event_type = 'SELF_DISCLOSURE_SETTINGS_CHANGED'"
                + " ORDER BY recorded_at DESC LIMIT 2",
            String.class);
    assertThat(changes)
        .anyMatch(a -> a.contains("enabled"))
        .anyMatch(a -> a.contains("dailyLimit"));
  }

  @Test
  void theSettingsAreOnlyForTheSystemAdministration() throws Exception {
    mockMvc.perform(get(SETTINGS).with(dev("dev-user"))).andExpect(status().isForbidden());
    mockMvc
        .perform(
            put(SETTINGS)
                .with(dev("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dailyLimit\":11}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anAbortedDeliveryConsumesNothingAndTheStatusOffersANewAttempt() throws Exception {
    SelfDisclosureService.Run run = service.start(ticketOf("dev-user"));
    assertThat(service.stream(run, new BreakingOutputStream(40))).isFalse();

    mockMvc
        .perform(get(STATUS).with(dev("dev-user")))
        .andExpect(jsonPath("$.completedToday").value(0))
        .andExpect(jsonPath("$.startsToday").value(1))
        .andExpect(jsonPath("$.lastResult").value("ABORTED"))
        .andExpect(jsonPath("$.retryPossible").value(true))
        .andExpect(jsonPath("$.running").value(false));
    String after =
        jdbc.queryForObject(
            "SELECT after FROM audit_log WHERE event_type = 'SELF_DISCLOSURE_EXPORT_FINISHED'"
                + " AND correlation_ref = ?",
            String.class,
            run.runId().toString());
    assertThat(after).contains("ABORTED").contains("CLIENT_DISCONNECTED").contains("sha256");
    assertThat(registry.active()).isZero();
  }

  @Test
  void anExportEndedByTheServerBreaksTheConnectionInsteadOfEndingItCleanly() throws Exception {
    manyNotifications();
    String ticket = ticketOf("dev-user");

    HttpResponse<InputStream> response = openDownload(ticket);
    assertThat(response.statusCode()).isEqualTo(200);
    try (InputStream body = response.body()) {
      body.readNBytes(4096);
      registry.stop();
      assertThatThrownBy(() -> readSlowly(body)).isInstanceOf(IOException.class);
    } finally {
      registry.start();
    }

    assertThat(awaitFinished()).contains("\"result\":\"ABORTED\"").contains("SHUTDOWN");
    mockMvc
        .perform(get(STATUS).with(dev("dev-user")))
        .andExpect(jsonPath("$.completedToday").value(0))
        .andExpect(jsonPath("$.lastResult").value("ABORTED"))
        .andExpect(jsonPath("$.retryPossible").value(true));
  }

  @Test
  void aClientThatGoesAwayOverARealConnectionConsumesNothing() throws Exception {
    manyNotifications();
    String ticket = ticketOf("dev-user");

    HttpResponse<InputStream> response = openDownload(ticket);
    try (InputStream body = response.body()) {
      body.readNBytes(4096);
    }

    assertThat(awaitFinished()).contains("\"result\":\"ABORTED\"").contains("CLIENT_DISCONNECTED");
    mockMvc
        .perform(get(STATUS).with(dev("dev-user")))
        .andExpect(jsonPath("$.completedToday").value(0))
        .andExpect(jsonPath("$.running").value(false));
  }

  @Test
  void theStartsOfADayAreBoundedAndARunningExportBlocksASecond() throws Exception {
    LocalDate today = LocalDate.now(ZoneId.systemDefault());
    jdbc.update(
        "INSERT INTO self_disclosure_usage (account_id, count_day, starts_count) VALUES (?, ?, 5)",
        person,
        Date.valueOf(today));
    mockMvc
        .perform(post(TICKETS).with(dev("dev-user")))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("SELF_DISCLOSURE_STARTS_EXHAUSTED"));
    mockMvc
        .perform(get(STATUS).with(dev("dev-user")))
        .andExpect(jsonPath("$.blocked").value("STARTS_EXHAUSTED"))
        .andExpect(jsonPath("$.remainingStarts").value(0));

    jdbc.update(
        "UPDATE self_disclosure_usage SET starts_count = 1, run_id = ?,"
            + " lease_until = clock_timestamp() + interval '2 minutes' WHERE account_id = ?",
        UUID.randomUUID(),
        person);
    mockMvc
        .perform(post(TICKETS).with(dev("dev-user")))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("SELF_DISCLOSURE_RUNNING"));

    jdbc.update(
        "UPDATE self_disclosure_usage SET lease_until = clock_timestamp() - interval '1 second'"
            + " WHERE account_id = ?",
        person);
    mockMvc
        .perform(get(STATUS).with(dev("dev-user")))
        .andExpect(jsonPath("$.running").value(false))
        .andExpect(jsonPath("$.lastResult").value("ABORTED"));
    download(ticketOf("dev-user"));
  }

  @Test
  void aTicketIsValidOnceAndNotAfterItsExpiry() throws Exception {
    String ticket = ticketOf("dev-user");
    download(ticket);
    mockMvc
        .perform(
            post(DOWNLOAD)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("ticket", ticket))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SELF_DISCLOSURE_TICKET_INVALID"));

    jdbc.update("UPDATE self_disclosure_settings SET daily_limit = 3 WHERE id = 1");
    String expired = ticketOf("dev-user");
    jdbc.update(
        "UPDATE self_disclosure_usage SET ticket_expires_at = clock_timestamp() - interval '1 second'"
            + " WHERE account_id = ?",
        person);
    mockMvc
        .perform(
            post(DOWNLOAD)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("ticket", expired))
        .andExpect(status().isNotFound());
  }

  @Test
  void aFullInstanceAnswers503WithoutConsumingTheTicket() throws Exception {
    String ticket = ticketOf("dev-user");
    List<Boolean> taken = new ArrayList<>();
    while (registry.tryAcquire()) {
      taken.add(true);
    }
    try {
      mockMvc
          .perform(
              post(DOWNLOAD)
                  .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                  .param("ticket", ticket))
          .andExpect(status().isServiceUnavailable())
          .andExpect(header().string("Retry-After", "60"));
    } finally {
      taken.forEach(t -> registry.release());
    }
    mockMvc
        .perform(get(STATUS).with(dev("dev-user")))
        .andExpect(jsonPath("$.startsToday").value(0));
    download(ticket);
  }

  @Test
  void twoRedemptionsOfOneTicketOnTwoInstancesYieldExactlyOneExport() throws Exception {
    String ticket = ticketOf("dev-user");
    List<SelfDisclosureService.Run> runs = new CopyOnWriteArrayList<>();
    List<RuntimeException> refusals = new CopyOnWriteArrayList<>();
    Runnable redeem =
        () -> {
          try {
            runs.add(service.start(ticket));
          } catch (RuntimeException refused) {
            refusals.add(refused);
          }
        };

    new TwoInstanceRace(dataSource)
        .run(
            redeem,
            redeem,
            "SELECT 1 FROM self_disclosure_usage WHERE account_id = ? FOR UPDATE",
            person);

    assertThat(runs).hasSize(1);
    assertThat(refusals).singleElement().isInstanceOf(NotFoundException.class);
    assertThat(service.stream(runs.getFirst(), OutputStream.nullOutputStream())).isTrue();
    assertThat(usage.find(person).orElseThrow().completedCount()).isEqualTo(1);
  }

  @Test
  void anotherAccountsExportHoldsNothingOfThePerson() throws Exception {
    notifications.notify(
        users.findById(person).orElseThrow().getOrganizationId(),
        person,
        NotificationType.GROUP_MEMBER_ADDED,
        null,
        null,
        "sd-test nur für dev-user",
        null);

    Map<String, String> entries =
        entries(download(ticketOf("dev-admin")).getResponse().getContentAsByteArray());

    assertThat(String.join("\n", entries.values()))
        .doesNotContain("sd-test nur für dev-user")
        .doesNotContain(person.toString())
        .doesNotContain("dev-user@opaa.local");
    assertThat(usage.find(person)).isEmpty();
  }

  @Test
  void theStartIsAnnouncedInTheApplicationAndByMail() throws Exception {
    LocalMailbox mailbox = new LocalMailbox(mailSettings, jdbc, admin);
    mailbox.start();
    try {
      download(ticketOf("dev-user"));

      assertThat(mailbox.waitFor(1, 15_000)).isTrue();
      MimeMessage mail = mailbox.messages()[0];
      assertThat(mail.getSubject()).contains("Selbstauskunft");
      assertThat(LocalMailbox.plainText(mail))
          .contains("wurde am")
          .contains("Falls Sie das nicht waren")
          .contains("Selbstauskunft Ihrer Daten");
    } finally {
      mailbox.stop();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = ?",
                Long.class,
                person,
                NotificationType.SELF_DISCLOSURE_STARTED.name()))
        .isEqualTo(1L);
  }

  @Test
  void theLastExportAppearsInNoAccountListOfTheAdministration() throws Exception {
    download(ticketOf("dev-user"));

    String list =
        mockMvc
            .perform(
                get("/api/v1/admin/accounts").with(dev("dev-admin")).param("query", "Dev User"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(list).containsIgnoringCase("dev user");
    assertThat(list.toLowerCase()).doesNotContain("disclosure").doesNotContain("lastcompleted");
  }

  @Test
  void anUnknownTicketIsRefusedAndGivesItsSlotBack() throws Exception {
    assertThatThrownBy(() -> service.start("unbekannt")).isInstanceOf(NotFoundException.class);
    assertThat(registry.active()).isZero();
  }

  private String ticketOf(String devUser) throws Exception {
    MvcResult result =
        mockMvc
            .perform(post(TICKETS).with(dev(devUser)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.expiresInSeconds").value(60))
            .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.ticket");
  }

  private MvcResult download(String ticket) throws Exception {
    return mockMvc
        .perform(
            post(DOWNLOAD)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("ticket", ticket))
        .andExpect(status().isOk())
        .andReturn();
  }

  /**
   * Enough notifications of hardly compressible text that the ZIP outgrows every socket buffer
   * between server and client, so the server is still writing when the test intervenes.
   */
  private void manyNotifications() {
    UUID organization = users.findById(person).orElseThrow().getOrganizationId();
    java.util.Random random = new java.util.Random(2989);
    List<Object[]> rows = new ArrayList<>();
    for (int i = 0; i < 12_000; i++) {
      byte[] noise = new byte[1500];
      random.nextBytes(noise);
      rows.add(
          new Object[] {
            UUID.randomUUID(),
            organization,
            person,
            "sd-test viel " + i,
            java.util.Base64.getEncoder().encodeToString(noise)
          });
    }
    jdbc.batchUpdate(
        "INSERT INTO notifications (id, organization_id, recipient_user_id, type, title, body,"
            + " created_at) VALUES (?, ?, ?, 'GROUP_MEMBER_ADDED', ?, ?, now())",
        rows);
  }

  private HttpResponse<InputStream> openDownload(String ticket) throws Exception {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .build()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + DOWNLOAD))
                .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8)))
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
  }

  /** Reads to the end at a pace the server outruns; an orderly end returns, a broken one throws. */
  private static void readSlowly(InputStream body) throws Exception {
    byte[] buffer = new byte[64 * 1024];
    while (body.read(buffer) >= 0) {
      Thread.sleep(5);
    }
  }

  /** The {@code after} of the end event of the person's latest export, once it is written. */
  private String awaitFinished() throws Exception {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      List<Map<String, Object>> events = eventsOf(person);
      if (events.size() == 2) {
        return String.valueOf(events.get(1).get("after"));
      }
      Thread.sleep(200);
    }
    throw new AssertionError("the export did not end within 60 seconds");
  }

  /** The two events of the person's latest export; the log keeps those of earlier methods. */
  private List<Map<String, Object>> eventsOf(UUID account) {
    String latest =
        jdbc.queryForObject(
            "SELECT a.correlation_ref FROM audit_log a"
                + " JOIN audit_actor_pseudonyms p ON p.pseudonym_id::text = a.actor_ref"
                + " WHERE p.user_id = ? AND a.event_type = 'SELF_DISCLOSURE_EXPORT_STARTED'"
                + " ORDER BY a.recorded_at DESC LIMIT 1",
            String.class,
            account);
    return jdbc.queryForList(
        "SELECT event_type, correlation_ref, object_type, actor_ref, subject_ref, after, outcome"
            + " FROM audit_log WHERE correlation_ref = ?"
            + " AND event_type LIKE 'SELF_DISCLOSURE_EXPORT_%'"
            + " ORDER BY recorded_at, event_type DESC",
        latest);
  }

  private UUID idOf(String subject) {
    return users.findBySubjectAndIssuer(subject, "opaa-dev").map(User::getId).orElseThrow();
  }

  private static Map<String, String> entries(byte[] zip) throws IOException {
    Map<String, String> entries = new java.util.LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return entries;
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static RequestPostProcessor dev(String devUser) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, devUser);
      return request;
    };
  }

  /** A client that goes away after {@code limit} bytes, as a closed connection does. */
  private static final class BreakingOutputStream extends OutputStream {

    private final int limit;
    private int written;

    BreakingOutputStream(int limit) {
      this.limit = limit;
    }

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      if (written + len > limit) {
        throw new IOException("Broken pipe");
      }
      written += len;
    }
  }
}
