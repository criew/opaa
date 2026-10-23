package io.opaa.selfdisclosure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.NotificationType;
import io.opaa.audit.AuditActorPseudonymService;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.test.LocalMailbox;
import io.opaa.test.OpaaIntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The identity categories of the self-disclosure in the delivered ZIP: German titles, every secret
 * column filled with a marker and none of them in the archive, only the person's own rows beside a
 * second account with rows of its own, and the anlassbezogene Klärung only as a hint. Works on the
 * two development accounts and removes the rows it wrote.
 */
@OpaaIntegrationTest
class IdentitySelfDisclosureIntegrationTest {

  private static final String TICKETS = "/api/v1/me/self-disclosure/tickets";
  private static final String DOWNLOAD = "/api/v1/me/self-disclosure/download";

  private static final List<String> SECRETS =
      List.of(
          "SECRET-PWHASH-2990",
          "SECRET-REFRESH-2990",
          "SECRET-ACTION-2990",
          "SECRET-JTI-2990",
          "SECRET-MAILPW-2990",
          "mailkonto-2990",
          "absender-2990@dienst.example",
          "Absendername 2990");

  private static final List<String> IDENTITY_FILES =
      List.of(
          "accounts.json",
          "local-credentials.json",
          "local-sessions-and-links.json",
          "account-state-history.json",
          "settings-editors.json",
          "audit-log.json");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository users;
  @Autowired private AuditActorPseudonymService pseudonyms;

  private User person;
  private User admin;
  private final List<UUID> auditEvents = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    mockMvc.perform(get("/api/v1/me/self-disclosure").with(dev("dev-admin")));
    mockMvc.perform(get("/api/v1/me/self-disclosure").with(dev("dev-user")));
    person = users.findBySubjectAndIssuer("dev-user", "opaa-dev").orElseThrow();
    admin = users.findBySubjectAndIssuer("dev-admin", "opaa-dev").orElseThrow();
    cleanUp();
  }

  @AfterEach
  void cleanUp() {
    for (User user : List.of(person, admin)) {
      jdbc.update("DELETE FROM self_disclosure_usage WHERE account_id = ?", user.getId());
      jdbc.update(
          "DELETE FROM notifications WHERE recipient_user_id = ? AND type = ?",
          user.getId(),
          NotificationType.SELF_DISCLOSURE_STARTED.name());
      jdbc.update("DELETE FROM local_refresh_tokens WHERE user_id = ?", user.getId());
      jdbc.update("DELETE FROM local_revoked_tokens WHERE user_id = ?", user.getId());
      jdbc.update("DELETE FROM local_action_tokens WHERE user_id = ?", user.getId());
      jdbc.update("DELETE FROM local_credentials WHERE user_id = ?", user.getId());
      jdbc.update(
          "DELETE FROM account_state_history WHERE user_id = ? AND cause = 'DIRECTORY_LOCKED'"
              + " AND valid_from < '2001-01-01'",
          user.getId());
      jdbc.update(
          "DELETE FROM audit_incident_scope_grants WHERE subject_user_id = ? AND reason LIKE ?",
          user.getId(),
          "%2990%");
    }
    for (UUID event : auditEvents) {
      jdbc.update("DELETE FROM audit_log WHERE event_id = ?", event);
    }
    auditEvents.clear();
    LocalMailbox.awaitOutboxDrained(jdbc);
  }

  @Test
  void theCategoriesCarryGermanTitlesAndOnlyThePersonsOwnRowsAndNoSecret() throws Exception {
    seedLocalAccount(person, "Anlagegrund-2990-eigen");
    seedLocalAccount(admin, "Anlagegrund-2990-fremd");
    jdbc.update(
        "UPDATE mail_settings SET password_ciphertext = ?, username = ?, from_address = ?,"
            + " from_name = ?, updated_by = ?, updated_at = now()",
        "SECRET-MAILPW-2990",
        "mailkonto-2990",
        "absender-2990@dienst.example",
        "Absendername 2990",
        person.getId());

    Map<String, String> zip = export();

    assertThat(zip.keySet()).containsAll(IDENTITY_FILES);
    assertThat(zip.get("accounts.json"))
        .contains("\"title\":\"Benutzerkonto\"", "\"label\":\"E-Mail-Adresse\"")
        .contains(person.getEmail());
    assertThat(zip.get("local-credentials.json"))
        .contains("\"title\":\"Lokales Konto\"", "Anlagegrund-2990-eigen");
    assertThat(zip.get("local-sessions-and-links.json"))
        .contains("\"title\":\"Sitzungen und Links\"", "ACCOUNT_LOCKED", "RESET_PASSWORD");
    assertThat(zip.get("account-state-history.json"))
        .contains("\"title\":\"Kontozustände\"", "DIRECTORY_LOCKED");
    assertThat(zip.get("settings-editors.json")).contains("E-Mail-Versand");
    String all = String.join("\n", zip.values());
    for (String secret : SECRETS) {
      assertThat(all).as("secret %s", secret).doesNotContain(secret);
    }
    String identity = String.join("\n", IDENTITY_FILES.stream().map(zip::get).toList());
    assertThat(identity).doesNotContain("2990-fremd").doesNotContain(admin.getId().toString());
    for (String name :
        java.util.stream.Stream.of(admin.getEmail(), admin.getDisplayName())
            .filter(java.util.Objects::nonNull)
            .toList()) {
      assertThat(identity).doesNotContain(name);
    }
    assertThat(all)
        .doesNotContain(
            pseudonyms.pseudonymFor(admin.getId(), admin.getOrganizationId()).toString())
        .doesNotContain(
            pseudonyms.pseudonymFor(person.getId(), person.getOrganizationId()).toString());
  }

  @Test
  void anIncidentClarificationAboutThePersonIsOnlyAHint() throws Exception {
    UUID grant = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO audit_incident_scope_grants (id, organization_id, subject_user_id,"
            + " scope_start, scope_end, purpose, reason, requested_by_user_id, requested_at,"
            + " status) VALUES (?, ?, ?, now() - interval '1 day', now(), 'SECURITY_INCIDENT', ?,"
            + " ?, now(), 'PENDING')",
        grant,
        person.getOrganizationId(),
        person.getId(),
        "Klärungsgrund 2990",
        admin.getId());
    UUID event = UUID.randomUUID();
    auditEvents.add(event);
    jdbc.update(
        "INSERT INTO audit_log (event_id, recorded_at, organization_id, actor_kind, actor_ref,"
            + " event_type, object_type, object_id, after, outcome, reason)"
            + " VALUES (?, ?, ?, 'USER', ?, 'AUDIT_LOG_ACCESSED', 'AUDIT_LOG', 'audit_log', ?,"
            + " 'SUCCESS', ?)",
        event,
        Timestamp.from(Instant.now()),
        person.getOrganizationId(),
        pseudonyms.pseudonymFor(admin.getId(), admin.getOrganizationId()).toString(),
        "{\"accessPath\":\"by-incident-scope\",\"scopeId\":\"" + grant + "\"}",
        "Abfrage 2990 im Vorgang");

    Map<String, String> zip = export();

    assertThat(zip.get("index.html")).contains("Anlassbezogene Klärungen über Ihre Person");
    assertThat(String.join("\n", zip.values()))
        .doesNotContain("Klärungsgrund 2990", "Abfrage 2990", grant.toString());
  }

  private void seedLocalAccount(User user, String createdReason) {
    Instant now = Instant.now();
    jdbc.update(
        "INSERT INTO local_credentials (user_id, password_hash, created_reason, email_verified_at)"
            + " VALUES (?, ?, ?, now())",
        user.getId(),
        user == person ? "SECRET-PWHASH-2990" : "fremd-hash-2990",
        createdReason);
    jdbc.update(
        "INSERT INTO local_refresh_tokens (id, family_id, user_id, token_lookup_hash, issued_at,"
            + " expires_at, family_expires_at, revoked_at, revocation_reason)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACCOUNT_LOCKED')",
        UUID.randomUUID(),
        UUID.randomUUID(),
        user.getId(),
        user == person ? "SECRET-REFRESH-2990" : "fremd-refresh-2990",
        Timestamp.from(now.minus(Duration.ofHours(2))),
        Timestamp.from(now.plus(Duration.ofHours(1))),
        Timestamp.from(now.plus(Duration.ofHours(8))),
        Timestamp.from(now.minus(Duration.ofHours(1))));
    jdbc.update(
        "INSERT INTO local_revoked_tokens (jti_hash, user_id, expires_at) VALUES (?, ?, ?)",
        user == person ? "SECRET-JTI-2990" : "fremd-jti-2990",
        user.getId(),
        Timestamp.from(now.plus(Duration.ofHours(1))));
    jdbc.update(
        "INSERT INTO local_action_tokens (id, user_id, purpose, token_hash, expires_at)"
            + " VALUES (?, ?, 'RESET_PASSWORD', ?, ?)",
        UUID.randomUUID(),
        user.getId(),
        user == person ? "SECRET-ACTION-2990" : "fremd-action-2990",
        Timestamp.from(now.plus(Duration.ofDays(1))));
    jdbc.update(
        "INSERT INTO account_state_history (id, user_id, organization_id, state, cause,"
            + " valid_from, valid_to) VALUES (?, ?, ?, 'LOCKED', 'DIRECTORY_LOCKED',"
            + " '2000-01-01T00:00:00Z', '2000-02-01T00:00:00Z')",
        UUID.randomUUID(),
        user.getId(),
        user.getOrganizationId());
  }

  private Map<String, String> export() throws Exception {
    String ticket =
        JsonPath.read(
            mockMvc
                .perform(post(TICKETS).with(dev("dev-user")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.ticket");
    byte[] zip =
        mockMvc
            .perform(
                post(DOWNLOAD)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("ticket", ticket))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    return entries(zip);
  }

  private static Map<String, String> entries(byte[] zip) throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return entries;
  }

  private static RequestPostProcessor dev(String devUser) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, devUser);
      return request;
    };
  }
}
