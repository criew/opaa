package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import io.opaa.mail.MailSettingsService;
import io.opaa.mail.MailTestSupport;
import io.opaa.security.SettingsEncryptor;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthLinkTest;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The optional mail step of the installation end to end, against an in-JVM SMTP server: it can be
 * skipped without holding up the completion, its check signs in without sending anything unless a
 * test recipient is named, a saved but failing step holds the completion up until it is checked or
 * skipped, and the completion stores the server and switches sending on. The password reaches no
 * answer, no audit entry and no payload; the test recipient is neither stored nor audited.
 */
@OpaaLocalAuthLinkTest
class InstallationMailStepIntegrationTest {

  private static final String BASE = "/api/v1/installation";
  private static final String SMTP_USER = "kennung";
  private static final String SMTP_PASSWORD = "Passwort-des-Mailservers";
  private static final String RECIPIENT = "it-betrieb@stadt.example";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private InstallationService installation;
  @Autowired private DatabaseInstallationStatus status;
  @Autowired private LocalAccountFixturesFactory accountsFactory;
  @Autowired private MailSettingsService mailSettings;
  @Autowired private SettingsEncryptor encryptor;

  private InstallationFixture fixture;
  private GreenMail greenMail;
  private Map<String, Object> mailSettingsBefore;

  @BeforeEach
  void setUp() throws Exception {
    mailSettingsBefore =
        jdbc.queryForMap(
            "SELECT enabled, host, port, username, password_ciphertext, encryption, from_address,"
                + " from_name FROM mail_settings WHERE id = 1");
    fixture = new InstallationFixture(jdbc, installation, status, accountsFactory.create());
    greenMail = new GreenMail(new ServerSetup(freePort(), "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
    greenMail.setUser("opaa@stadt.example", SMTP_USER, SMTP_PASSWORD);
    greenMail.start();
  }

  @AfterEach
  void tearDown() {
    greenMail.stop();
    fixture.close();
    jdbc.update(
        "UPDATE mail_settings SET enabled = ?, host = ?, port = ?, username = ?,"
            + " password_ciphertext = ?, encryption = ?, from_address = ?, from_name = ?"
            + " WHERE id = 1",
        mailSettingsBefore.get("enabled"),
        mailSettingsBefore.get("host"),
        mailSettingsBefore.get("port"),
        mailSettingsBefore.get("username"),
        mailSettingsBefore.get("password_ciphertext"),
        mailSettingsBefore.get("encryption"),
        mailSettingsBefore.get("from_address"),
        mailSettingsBefore.get("from_name"));
    MailTestSupport.resetCaches(mailSettings);
  }

  @Test
  void theMailStepIsOptionalAndASkippedStepLeavesSendingOff() throws Exception {
    completeMandatorySteps();
    mockMvc
        .perform(authorized(get(BASE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.completable").value(true))
        .andExpect(jsonPath("$.publicBaseUrlConfigured").value(true))
        .andExpect(jsonPath("$.steps[4].step").value("MAIL"))
        .andExpect(jsonPath("$.steps[4].mandatory").value(false))
        .andExpect(jsonPath("$.steps[4].saved").value(false));

    mockMvc
        .perform(authorized(post(BASE + "/steps/MAIL/skip")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.saved").value(false));
    mockMvc.perform(authorized(post(BASE + "/complete"))).andExpect(status().isOk());

    assertThat(jdbc.queryForObject("SELECT enabled FROM mail_settings WHERE id = 1", Boolean.class))
        .isEqualTo(mailSettingsBefore.get("enabled"));
    assertThat(auditTypes()).contains("INSTALLATION_STEP_SKIPPED", "INSTALLATION_COMPLETED");
    assertThat(auditAfter("INSTALLATION_COMPLETED")).contains("\"mailEnabled\":false");
  }

  @Test
  void aCheckedMailStepIsTakenOverAndSwitchesSendingOn() throws Exception {
    completeMandatorySteps();
    saveMail(SMTP_PASSWORD)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mail.passwordSet").value(true))
        .andExpect(jsonPath("$.mail.password").doesNotExist());

    check("MAIL", null)
        .andExpect(jsonPath("$.checked").value(true))
        .andExpect(jsonPath("$.checkMessage").value(Matchers.containsString("Anmeldung")));
    assertThat(greenMail.getReceivedMessages()).isEmpty();

    mockMvc.perform(authorized(post(BASE + "/complete"))).andExpect(status().isOk());

    Map<String, Object> stored =
        jdbc.queryForMap(
            "SELECT enabled, host, port, username, password_ciphertext, encryption, from_address"
                + " FROM mail_settings WHERE id = 1");
    assertThat(stored.get("enabled")).isEqualTo(true);
    assertThat(stored.get("host")).isEqualTo("127.0.0.1");
    assertThat(stored.get("port")).isEqualTo(greenMail.getSmtp().getPort());
    assertThat(stored.get("username")).isEqualTo(SMTP_USER);
    assertThat(stored.get("encryption")).isEqualTo("NONE");
    assertThat(stored.get("from_address")).isEqualTo("opaa@stadt.example");
    assertThat(encryptor.decrypt((String) stored.get("password_ciphertext")))
        .isEqualTo(SMTP_PASSWORD);
    assertThat(mailSettings.snapshot().sendable()).isTrue();
    assertThat(auditTypes()).contains("MAIL_SETTINGS_CHANGED");
    assertThat(auditAfter("INSTALLATION_COMPLETED")).contains("\"mailEnabled\":true");
    assertThat(auditPayloads()).noneMatch(payload -> payload.contains(SMTP_PASSWORD));
  }

  @Test
  void theTestMessageGoesToTheNamedAddressWhichIsNeitherStoredNorAudited() throws Exception {
    completeMandatorySteps();
    saveMail(SMTP_PASSWORD).andExpect(status().isOk());

    check("MAIL", RECIPIENT).andExpect(jsonPath("$.checked").value(true));

    assertThat(greenMail.waitForIncomingEmail(10_000, 1)).isTrue();
    assertThat(greenMail.getReceivedMessages()[0].getAllRecipients()[0].toString())
        .isEqualTo(RECIPIENT);
    assertThat(
            jdbc.queryForObject(
                "SELECT payload::text || coalesce(check_message, '') FROM installation_steps"
                    + " WHERE step = 'MAIL'",
                String.class))
        .doesNotContain(RECIPIENT);
    assertThat(auditPayloads()).noneMatch(payload -> payload.contains(RECIPIENT));
    assertThat(auditAfter("INSTALLATION_STEP_CHECKED")).contains("\"testMail\":true");
  }

  @Test
  void aSavedButFailingMailStepHoldsUpTheCompletionUntilItIsSkipped() throws Exception {
    completeMandatorySteps();
    saveMail("falsch").andExpect(status().isOk());
    check("MAIL", null).andExpect(jsonPath("$.checked").value(false));

    mockMvc.perform(authorized(get(BASE))).andExpect(jsonPath("$.completable").value(false));
    mockMvc
        .perform(authorized(post(BASE + "/complete")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("MAIL")));

    mockMvc.perform(authorized(post(BASE + "/steps/MAIL/skip"))).andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM installation_steps WHERE step = 'MAIL'", Integer.class))
        .isZero();
    mockMvc.perform(authorized(post(BASE + "/complete"))).andExpect(status().isOk());
    assertThat(jdbc.queryForObject("SELECT enabled FROM mail_settings WHERE id = 1", Boolean.class))
        .isEqualTo(mailSettingsBefore.get("enabled"));
  }

  @Test
  void onlyTheMailStepIsSkippedAndOnlyItSendsATestMessage() throws Exception {
    mockMvc
        .perform(authorized(post(BASE + "/steps/CHAT_MODEL/skip")))
        .andExpect(status().isBadRequest());
    saveFirstAdmin();
    mockMvc
        .perform(
            authorized(post(BASE + "/steps/FIRST_ADMIN/check"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"testRecipient\":\"" + RECIPIENT + "\"}"))
        .andExpect(status().isBadRequest());
    saveMail(SMTP_PASSWORD).andExpect(status().isOk());
    mockMvc
        .perform(
            authorized(post(BASE + "/steps/MAIL/check"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"testRecipient\":\"keine-adresse\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("testRecipient"));
    assertThat(greenMail.getReceivedMessages()).isEmpty();
  }

  @Test
  void anInternalMailServerIsAFieldErrorUnlessReleased() throws Exception {
    mockMvc
        .perform(
            authorized(put(BASE + "/steps/MAIL"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"mail\":{\"host\":\"10.20.30.40\",\"port\":25,"
                        + "\"fromAddress\":\"opaa@stadt.example\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("mail.host"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("BLOCKED"));
  }

  @Test
  void anAnswerWithoutMailStaysValidAndOneWithMailSwitchesSendingOn() throws Exception {
    saveMail(SMTP_PASSWORD).andExpect(status().isOk());

    mockMvc
        .perform(
            authorized(post(BASE + "/answer-file"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(answers(null)))
        .andExpect(status().isOk());

    assertThat(jdbc.queryForObject("SELECT enabled FROM mail_settings WHERE id = 1", Boolean.class))
        .isEqualTo(mailSettingsBefore.get("enabled"));
    assertThat(auditAfter("INSTALLATION_STEP_SKIPPED")).contains("\"discarded\":true");
  }

  @Test
  void anAnswerWithMailIsCheckedAndSwitchesSendingOn() throws Exception {
    mockMvc
        .perform(
            authorized(post(BASE + "/answer-file"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(answers(mailJson(SMTP_PASSWORD))))
        .andExpect(status().isOk());

    assertThat(jdbc.queryForObject("SELECT enabled FROM mail_settings WHERE id = 1", Boolean.class))
        .isTrue();
    assertThat(greenMail.getReceivedMessages()).isEmpty();
  }

  private void completeMandatorySteps() throws Exception {
    saveFirstAdmin();
    check("FIRST_ADMIN", null).andExpect(jsonPath("$.checked").value(true));
    mockMvc
        .perform(
            authorized(put(BASE + "/steps/SIGN_IN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"signIn\":{\"mode\":\"LOCAL_ONLY\"}}"))
        .andExpect(status().isOk());
    check("SIGN_IN", null).andExpect(jsonPath("$.checked").value(true));
    mockMvc
        .perform(
            authorized(put(BASE + "/steps/CHAT_MODEL"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{" + chatModelPart() + "}"))
        .andExpect(status().isOk());
    check("CHAT_MODEL", null).andExpect(jsonPath("$.checked").value(true));
    mockMvc
        .perform(
            authorized(put(BASE + "/steps/EMBEDDING_MODEL"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{" + embeddingModelPart() + "}"))
        .andExpect(status().isOk());
    check("EMBEDDING_MODEL", null).andExpect(jsonPath("$.checked").value(true));
  }

  private void saveFirstAdmin() throws Exception {
    mockMvc
        .perform(
            authorized(put(BASE + "/steps/FIRST_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstAdmin\":" + firstAdminJson() + "}"))
        .andExpect(status().isOk());
  }

  private ResultActions saveMail(String password) throws Exception {
    return mockMvc.perform(
        authorized(put(BASE + "/steps/MAIL"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"mail\":" + mailJson(password) + "}"));
  }

  private String mailJson(String password) {
    return "{\"host\":\"127.0.0.1\",\"port\":"
        + greenMail.getSmtp().getPort()
        + ",\"encryption\":\"NONE\",\"username\":\""
        + SMTP_USER
        + "\",\"password\":\""
        + password
        + "\",\"fromAddress\":\"opaa@stadt.example\",\"fromName\":\"OPAA\"}";
  }

  private String answers(String mail) {
    return "{\"firstAdmin\":"
        + firstAdminJson()
        + ",\"signIn\":{\"mode\":\"LOCAL_ONLY\"},"
        + chatModelPart()
        + ","
        + embeddingModelPart()
        + (mail == null ? "" : ",\"mail\":" + mail)
        + "}";
  }

  private String chatModelPart() {
    return "\"chatModel\":{\"baseUrl\":\"" + fixture.chatBaseUrl() + "\",\"model\":\"qwen2.5:7b\"}";
  }

  private String embeddingModelPart() {
    return "\"embeddingModel\":{\"baseUrl\":\""
        + fixture.chatBaseUrl()
        + "\",\"model\":\"bge-m3\"}";
  }

  private static String firstAdminJson() {
    return "{\"email\":\""
        + InstallationFixture.ADMIN_EMAIL
        + "\",\"displayName\":\"IT-Betrieb\",\"password\":\""
        + InstallationFixture.ADMIN_PASSWORD
        + "\"}";
  }

  private ResultActions check(String step, String testRecipient) throws Exception {
    MockHttpServletRequestBuilder request = authorized(post(BASE + "/steps/" + step + "/check"));
    if (testRecipient != null) {
      request
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"testRecipient\":\"" + testRecipient + "\"}");
    }
    return mockMvc.perform(request).andExpect(status().isOk());
  }

  private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", "Bearer " + InstallationFixture.TOKEN);
  }

  private List<String> auditTypes() {
    return jdbc.queryForList(
        "SELECT event_type FROM audit_log WHERE actor_ref = ? ORDER BY recorded_at",
        String.class,
        InstallationService.SYSTEM_ACTOR);
  }

  private String auditAfter(String type) {
    return jdbc.queryForObject(
        "SELECT after::text FROM audit_log WHERE actor_ref = ? AND event_type = ?"
            + " ORDER BY recorded_at DESC LIMIT 1",
        String.class,
        InstallationService.SYSTEM_ACTOR,
        type);
  }

  private List<String> auditPayloads() {
    return jdbc.queryForList(
        "SELECT coalesce(before::text, '') || coalesce(after::text, '') FROM audit_log"
            + " WHERE actor_ref = ?",
        String.class,
        InstallationService.SYSTEM_ACTOR);
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
